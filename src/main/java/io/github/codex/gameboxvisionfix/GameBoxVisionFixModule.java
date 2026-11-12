package io.github.codex.gameboxvisionfix;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Repairs a cold-start race in MIUI Security Center.
 *
 * The target class, methods, and fields are intentionally not named here. DexKit discovers the
 * implementation from unique log markers, so the repair keeps working when R8 changes identifiers.
 */
public final class GameBoxVisionFixModule extends XposedModule {
    private static final String TAG = "GameBoxVisionFix";
    private static final String TARGET = "com.miui.securitycenter";
    private static final String UI_PROCESS = TARGET + ":ui";

    private static final String INIT_MARKER = "Initialization completed, curPkg = ";
    private static final String CLEANUP_MARKER = "release service...";

    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final long FIRST_RETRY_DELAY_MS = 500L;
    private static final long RETRY_INTERVAL_MS = 900L;

    private final AtomicBoolean installed = new AtomicBoolean();
    private final ThreadLocal<Boolean> insideInitialization = new ThreadLocal<>();
    private final ThreadLocal<Boolean> cleanupDeferred = new ThreadLocal<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile XposedInterface.Invoker<?, Method> initializeInvoker;
    private volatile XposedInterface.Invoker<?, Method> cleanupInvoker;
    private volatile Object recoveryToken;
    private volatile boolean targetProcess;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        String process = param.getProcessName();
        targetProcess = TARGET.equals(process) || UI_PROCESS.equals(process);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!targetProcess || !TARGET.equals(param.getPackageName())
                || !installed.compareAndSet(false, true)) {
            return;
        }
        ClassLoader loader = param.getClassLoader();
        Thread discovery = new Thread(() -> installHooks(loader), "GameBoxVisionFix-DexKit");
        discovery.setDaemon(true);
        discovery.start();
    }

    private void installHooks(ClassLoader loader) {
        try (DexKitBridge bridge = openBridge(loader)) {
            MethodData initializeData = findUnique(bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(INIT_MARKER)
                            .returnType("void")
                            .paramCount(0))), "initialization");

            ClassData ownerData = initializeData.getDeclaredClass();
            if (ownerData == null) {
                throw new IllegalStateException("DexKit returned no declaring class");
            }

            MethodData cleanupData = findUnique(ownerData.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(CLEANUP_MARKER)
                            .returnType("void")
                            .paramCount(0))), "service cleanup");

            Method initialize = initializeData.getMethodInstance(loader);
            Method cleanup = cleanupData.getMethodInstance(loader);
            installRepairHooks(initialize, cleanup);

            log(Log.INFO, TAG, "DexKit resolved repair hooks: " + initializeData
                    + "; " + cleanupData);
        } catch (Throwable error) {
            installed.set(false);
            log(Log.ERROR, TAG, "DexKit hook setup failed; no behavior changed", error);
        }
    }

    private static DexKitBridge openBridge(ClassLoader loader) {
        System.loadLibrary("dexkit");
        return DexKitBridge.create(loader, true);
    }

    private void installRepairHooks(Method initialize, Method cleanup) {
            cleanupInvoker = getInvoker(cleanup).setType(XposedInterface.Invoker.Type.ORIGIN);
            initializeInvoker = getInvoker(initialize).setType(XposedInterface.Invoker.Type.ORIGIN);

            hook(cleanup).setId("gamebox-vision:cleanup").intercept(chain -> {
                Object owner = chain.getThisObject();
                if (owner == null) {
                    return chain.proceed();
                }
                synchronized (owner) {
                    if (Boolean.TRUE.equals(insideInitialization.get())) {
                        // Cleanup clears the current package and switch state. Retrying after it
                        // cannot restore the session; defer it until the bounded retry is exhausted.
                        cleanupDeferred.set(Boolean.TRUE);
                        scheduleRecovery(owner);
                        return null;
                    }
                    recoveryToken = null;
                    return chain.proceed();
                }
            });

            hook(initialize).setId("gamebox-vision:initialize").intercept(chain -> {
                synchronized (chain.getThisObject()) {
                    insideInitialization.set(Boolean.TRUE);
                    cleanupDeferred.set(Boolean.FALSE);
                    try {
                        Object result = chain.proceed();
                        if (!Boolean.TRUE.equals(cleanupDeferred.get())) {
                            recoveryToken = null;
                        }
                        return result;
                    } finally {
                        insideInitialization.remove();
                        cleanupDeferred.remove();
                    }
                }
            });

    }

    private static MethodData findUnique(MethodDataList candidates, String label) {
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + " method, found " + candidates.size());
        }
        return candidates.get(0);
    }

    private void scheduleRecovery(Object owner) {
        if (recoveryToken != null) {
            return;
        }
        Object token = new Object();
        recoveryToken = token;
        log(Log.WARN, TAG, "Deferring failed initialization cleanup; retaining game session for retry");
        mainHandler.postDelayed(() -> attemptRecovery(owner, token, 1), FIRST_RETRY_DELAY_MS);
    }

    private void attemptRecovery(Object owner, Object token, int attempt) {
        synchronized (owner) {
            if (recoveryToken != token) {
                return;
            }
            insideInitialization.set(Boolean.TRUE);
            cleanupDeferred.set(Boolean.FALSE);
            boolean failed = true;
            try {
                initializeInvoker.invoke(owner);
                failed = Boolean.TRUE.equals(cleanupDeferred.get());
                log(Log.INFO, TAG, "Vision-enhance retry " + attempt
                        + (failed ? " still requires cleanup" : " completed without cleanup"));
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Initialization retry failed on attempt " + attempt, error);
            } finally {
                insideInitialization.remove();
                cleanupDeferred.remove();
            }
            if (!failed) {
                recoveryToken = null;
                return;
            }
            if (attempt < MAX_RECOVERY_ATTEMPTS) {
                mainHandler.postDelayed(
                        () -> attemptRecovery(owner, token, attempt + 1), RETRY_INTERVAL_MS);
            } else {
                recoveryToken = null;
                try {
                    cleanupInvoker.invoke(owner);
                    log(Log.WARN, TAG, "Vision-enhance retries exhausted; original cleanup completed");
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "Original cleanup failed after retries", error);
                }
            }
        }
    }
}
