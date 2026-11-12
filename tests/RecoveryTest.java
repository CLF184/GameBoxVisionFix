import android.os.Handler;
import io.github.codex.gameboxvisionfix.GameBoxVisionFixModule;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;

/** Runs production interceptors with a fake framework and deterministic Android message queue. */
public class RecoveryTest {
    static final Map<Method, XposedInterface.Hooker> hooks = new HashMap<>();
    static final Method INIT, CLEANUP;
    static {
        try {
            INIT = Session.class.getMethod("initialize");
            CLEANUP = Session.class.getMethod("cleanup");
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    public static class Session {
        String pkg = "supported.game";
        boolean service = true, enabled = true, ready, throwsOnInit;
        int initializations, cleanups;
        public void initialize() throws Throwable {
            initializations++;
            if (throwsOnInit) throw new IllegalStateException("binder failure");
            // Match the ROM's early return and destructive cleanup semantics.
            if (!service || pkg == null) return;
            if (!ready || !enabled) dispatch(CLEANUP, this);
        }
        public void cleanup() {
            cleanups++;
            pkg = null;
            service = false;
            enabled = false;
        }
    }

    static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(RecoveryTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }
    static Object original(Method method, Object owner) throws Throwable {
        try { return method.invoke(owner); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    static Object dispatch(Method method, Object owner) throws Throwable {
        XposedInterface.Hooker hook = hooks.get(method);
        if (hook == null) return original(method, owner);
        return hook.intercept((XposedInterface.Chain) proxy(XposedInterface.Chain.class,
                (self, called, args) -> switch (called.getName()) {
                    case "getThisObject" -> owner;
                    case "getExecutable" -> method;
                    case "proceed" -> original(method, owner);
                    default -> throw new AssertionError(called);
                }));
    }
    static void setup() throws Exception {
        hooks.clear();
        Handler.pending.clear();
        var module = new GameBoxVisionFixModule();
        var framework = (XposedInterface) proxy(XposedInterface.class, (self, called, args) -> {
            if (called.getName().equals("log")) return null;
            if (called.getName().equals("getInvoker")) {
                Method method = (Method) args[0];
                return proxy(XposedInterface.Invoker.class, (invoker, operation, values) -> {
                    if (operation.getName().equals("setType")) return invoker;
                    return original(method, values[0]);
                });
            }
            if (called.getName().equals("hook")) {
                Method method = (Method) args[0];
                return proxy(XposedInterface.HookBuilder.class, (builder, operation, values) -> {
                    if (operation.getName().equals("intercept")) {
                        hooks.put(method, (XposedInterface.Hooker) values[0]);
                        return null;
                    }
                    return builder;
                });
            }
            throw new AssertionError(called);
        });
        module.attachFramework(framework, () -> {});
        Method install = GameBoxVisionFixModule.class.getDeclaredMethod(
                "installRepairHooks", Method.class, Method.class);
        install.setAccessible(true);
        install.invoke(module, INIT, CLEANUP);
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Throwable {
        setup();
        Session session = new Session();
        dispatch(INIT, session);
        check(session.pkg != null && session.service && session.enabled && session.cleanups == 0,
                "Failure must retain the complete session before retry");
        session.ready = true;
        Handler.drain();
        check(session.initializations == 2 && session.cleanups == 0,
                "Success must stop retries immediately");

        setup();
        session = new Session();
        dispatch(INIT, session);
        Handler.drain();
        check(session.initializations == 4 && session.cleanups == 1 && session.pkg == null,
                "Exhaustion must run original cleanup exactly once after three retries");

        setup();
        session = new Session();
        dispatch(INIT, session);
        dispatch(CLEANUP, session);
        Handler.drain();
        check(session.initializations == 1 && session.cleanups == 1 && !session.service,
                "Normal exit must cancel pending recovery without rebinding");

        setup();
        session = new Session();
        session.ready = true;
        dispatch(INIT, session);
        dispatch(CLEANUP, session);
        check(Handler.pending.isEmpty() && session.cleanups == 1,
                "Successful initialization followed by normal cleanup must not schedule recovery");

        setup();
        session = new Session();
        dispatch(INIT, session);
        session.throwsOnInit = true;
        Handler.drain();
        check(session.initializations == 4 && session.cleanups == 1,
                "Retry exceptions must remain bounded and end in original cleanup");
        System.out.println("PASS: retained session, early success, bounded exhaustion, exit cancellation, normal cleanup, retry exceptions");
    }
}
