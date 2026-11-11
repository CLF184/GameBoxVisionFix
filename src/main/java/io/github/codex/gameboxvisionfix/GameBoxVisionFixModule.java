package io.github.codex.gameboxvisionfix;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final String CAPABILITY_MARKER =
            "needInitService isDeviceSupport = %s, isDeviceSupportFRC = %s, "
                    + "isSupportResolution = %s, isSupportDualDPU = %s";
    private static final String CLEANUP_MARKER = "release service...";
    private static final String REBIND_MARKER = "startService fail. service not null, pkg = ";

    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final long FIRST_RETRY_DELAY_MS = 500L;
    private static final long SERVICE_SETTLE_DELAY_MS = 900L;
    private static final long RETRY_INTERVAL_MS = 900L;
    private static final long RECENT_CHECK_WINDOW_MS = 5000L;

    static {
        System.loadLibrary("dexkit");
    }

    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean recoveryScheduled = new AtomicBoolean();
    private final ThreadLocal<Boolean> insideInitialization = new ThreadLocal<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile XposedInterface.Invoker<?, Method> initializeInvoker;
    private volatile XposedInterface.Invoker<?, Method> rebindInvoker;
    private volatile long lastCapabilityCheckMs = -RECENT_CHECK_WINDOW_MS - 1;
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
        try (DexKitBridge bridge = DexKitBridge.create(loader, true)) {
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

            MethodData capabilityData = findUnique(ownerData.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(CAPABILITY_MARKER)
                            .returnType("boolean")
                            .paramCount(0))), "capability check");

            MethodData rebindData = findUnique(ownerData.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(REBIND_MARKER)
                            .returnType("void")
                            .paramTypes("java.lang.String"))), "service rebind");

            Method initialize = initializeData.getMethodInstance(loader);
            Method cleanup = cleanupData.getMethodInstance(loader);
            Method capability = capabilityData.getMethodInstance(loader);
            Method rebind = rebindData.getMethodInstance(loader);

            initializeInvoker = getInvoker(initialize);
            rebindInvoker = getInvoker(rebind);

            hook(cleanup).setId("gamebox-vision:cleanup").intercept(chain -> {
                boolean failedInitialization = Boolean.TRUE.equals(insideInitialization.get());
                boolean recentCapabilityCheck =
                        SystemClock.elapsedRealtime() - lastCapabilityCheckMs
                                <= RECENT_CHECK_WINDOW_MS;
                Object owner = chain.getThisObject();
                Object result = chain.proceed();
                if (owner != null && (failedInitialization || recentCapabilityCheck)) {
                    scheduleRecovery(owner, failedInitialization
                            ? "initialization failure"
                            : "recent capability check");
                }
                return result;
            });

            hook(capability).setId("gamebox-vision:capability").intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    lastCapabilityCheckMs = SystemClock.elapsedRealtime();
                }
            });

            hook(initialize).setId("gamebox-vision:initialize").intercept(chain -> {
                insideInitialization.set(Boolean.TRUE);
                try {
                    return chain.proceed();
                } finally {
                    insideInitialization.remove();
                }
            });

            log(Log.INFO, TAG, "DexKit resolved repair hooks: " + initializeData
                    + "; " + capabilityData + "; " + cleanupData + "; " + rebindData);
        } catch (Throwable error) {
            installed.set(false);
            log(Log.ERROR, TAG, "DexKit hook setup failed; no behavior changed", error);
        }
    }

    private static MethodData findUnique(MethodDataList candidates, String label) {
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Expected one " + label + " method, found " + candidates.size());
        }
        return candidates.get(0);
    }

    private void scheduleRecovery(Object owner, String reason) {
        if (!recoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        log(Log.WARN, TAG, "Vision-enhance released the service after " + reason
                + "; scheduling retry");
        mainHandler.postDelayed(() -> attemptRecovery(owner, 1), FIRST_RETRY_DELAY_MS);
    }

    private void attemptRecovery(Object owner, int attempt) {
        if (!recoveryScheduled.get()) {
            return;
        }
        try {
            rebindInvoker.invoke(owner, "GameBoxVisionFix");
            log(Log.INFO, TAG, "Re-bound Joyose service for recovery attempt " + attempt);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Service rebind failed on attempt " + attempt, error);
            finishRecovery();
            return;
        }

        mainHandler.postDelayed(() -> {
            try {
                initializeInvoker.invoke(owner);
                log(Log.INFO, TAG, "Re-ran vision-enhance initialization, attempt " + attempt);
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Initialization retry failed on attempt " + attempt, error);
                finishRecovery();
                return;
            }

            if (attempt < MAX_RECOVERY_ATTEMPTS) {
                mainHandler.postDelayed(
                        () -> attemptRecovery(owner, attempt + 1), RETRY_INTERVAL_MS);
            } else {
                mainHandler.postDelayed(this::finishRecovery, RETRY_INTERVAL_MS);
            }
        }, SERVICE_SETTLE_DELAY_MS);
    }

    private void finishRecovery() {
        recoveryScheduled.set(false);
        log(Log.INFO, TAG, "Vision-enhance recovery window finished");
    }
}
