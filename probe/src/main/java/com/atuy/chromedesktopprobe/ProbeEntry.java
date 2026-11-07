package com.atuy.chromedesktopprobe;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Read-only probe for Chrome Desktop Android builds on a phone.
 *
 * This module hooks only Android framework lifecycle methods. It never hooks
 * Chromium internals and never overrides return values, changes settings,
 * modifies UI, or reads page URLs / titles / browsing history.
 *
 * Values are marked DIRECT (reflected Chromium value), ANDROID (device API),
 * DERIVED (from upstream Chromium 158 source), or UNAVAILABLE (R8/hidden).
 */
public final class ProbeEntry extends XposedModule {
    private static final String TAG = "ChromeDesktopProbe";
    private static final String CHROME = "com.android.chrome";
    private static final int MAX_REPORTS = 3;

    private volatile boolean isChromeProcess;
    private volatile Context chromeContext;
    private volatile ClassLoader chromeLoader;
    private final AtomicInteger reports = new AtomicInteger(0);

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        isChromeProcess = CHROME.equals(param.getProcessName());
        if (!isChromeProcess) return;

        emit("BOOT", "process=" + param.getProcessName() + " api=" + getApiVersion(),
                "DIRECT");
        installLifecycleCallbacks();
        scheduleReport("process-start", 2500L);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!isTarget(param.getPackageName(), param.isFirstPackage())) return;
        chromeLoader = param.getDefaultClassLoader();
        emit("lifecycle", "onPackageLoaded", "DIRECT");
        scheduleReport("package-loaded", 1800L);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!isTarget(param.getPackageName(), param.isFirstPackage())) return;
        chromeLoader = param.getClassLoader();
        emit("lifecycle", "onPackageReady", "DIRECT");
        scheduleReport("package-ready", 1200L);
    }

    private boolean isTarget(String packageName, boolean first) {
        return isChromeProcess && first && CHROME.equals(packageName);
    }

    private void installLifecycleCallbacks() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach).intercept(chain -> {
                Object[] arguments = chain.getArgs().toArray(new Object[0]);
                Object outcome = chain.proceed(arguments);
                if (arguments.length == 1 && arguments[0] instanceof Context) {
                    Context ctx = (Context) arguments[0];
                    if (CHROME.equals(ctx.getPackageName())) {
                        chromeContext = ctx;
                        if (chromeLoader == null) chromeLoader = ctx.getClassLoader();
                        scheduleReport("Application.attach", 1500L);
                    }
                }
                return outcome;
            });
            emit("lifecycle", "Application.attach observer installed", "DIRECT");
        } catch (Throwable t) {
            emit("lifecycle", "Application.attach observer UNAVAILABLE: "
                    + shortError(t), "UNAVAILABLE");
        }

        try {
            Method resume = Activity.class.getDeclaredMethod("onResume");
            resume.setAccessible(true);
            hook(resume).intercept(chain -> {
                Object outcome = chain.proceed(chain.getArgs().toArray(new Object[0]));
                Object object = chain.getThisObject();
                if (object instanceof Activity) {
                    Activity activity = (Activity) object;
                    if (CHROME.equals(activity.getPackageName())) {
                        chromeContext = activity;
                        chromeLoader = activity.getClassLoader();
                        scheduleReport("Activity.onResume", 1200L);
                    }
                }
                return outcome;
            });
            emit("lifecycle", "Activity.onResume observer installed", "DIRECT");
        } catch (Throwable t) {
            emit("lifecycle", "Activity.onResume observer UNAVAILABLE: "
                    + shortError(t), "UNAVAILABLE");
        }
    }

    private void scheduleReport(String origin, long delayMs) {
        if (!isChromeProcess) return;
        new Handler(Looper.getMainLooper()).postDelayed(() -> report(origin), delayMs);
    }

    private synchronized void report(String origin) {
        if (!isChromeProcess || reports.get() >= MAX_REPORTS) return;
        Context ctx = chromeContext;
        if (ctx == null) {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Method m = at.getDeclaredMethod("currentApplication");
                m.setAccessible(true);
                Object app = m.invoke(null);
                if (app instanceof Application) ctx = (Application) app;
            } catch (Throwable ignored) {
                // onPackageLoaded may occur before Application.attach.
            }
        }
        if (ctx == null) {
            emit("snapshot", "waiting for Chrome Context (" + origin + ")", "UNAVAILABLE");
            return;
        }
        if (!CHROME.equals(ctx.getPackageName())) return;
        chromeContext = ctx;

        int snapshot = reports.incrementAndGet();
        emit("SNAPSHOT_BEGIN", "index=" + snapshot + " trigger=" + origin, "DIRECT");
        if (snapshot == 1) {
            emit("classLoader.chrome", chromeLoader == null
                    ? "null" : chromeLoader.getClass().getName(), "DIAGNOSTIC");
            emit("classLoader.context", ctx.getClassLoader() == null
                    ? "null" : ctx.getClassLoader().getClass().getName(),
                    "DIAGNOSTIC");
        }

        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(CHROME, 0);
            emit("chrome.version", info.versionName, "ANDROID");
        } catch (Throwable t) {
            emit("chrome.version", "UNAVAILABLE: " + shortError(t), "UNAVAILABLE");
        }

        final boolean hasPcFeature =
                ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PC);
        emit("android.hardware.type.pc", hasPcFeature, "ANDROID");

        Configuration config = ctx.getResources().getConfiguration();
        emit("android.smallestScreenWidthDp", config.smallestScreenWidthDp, "ANDROID");
        emit("android.screenWidthDp", config.screenWidthDp, "ANDROID");
        emit("android.screenLayoutSize",
                config.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK, "ANDROID");

        Boolean resourceTablet = getTabletBucket(ctx);
        emit("chromium.tabletResourceBucket",
                resourceTablet == null ? "UNAVAILABLE" : resourceTablet,
                resourceTablet == null ? "UNAVAILABLE" : "CHROME_RESOURCE");

        boolean physicalAlphabeticKeyboard = false;
        boolean physicalPrecisionPointer = false;
        boolean inputDeviceScanSucceeded = false;
        int physicalDevices = 0;
        try {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null || device.isVirtual()) continue;
                physicalDevices++;
                if (device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    physicalAlphabeticKeyboard = true;
                }
                if (device.supportsSource(InputDevice.SOURCE_MOUSE)) {
                    physicalPrecisionPointer = true;
                }
            }
            inputDeviceScanSucceeded = true;
            emit("android.physicalInputDevices", physicalDevices, "ANDROID");
            emit("android.alphabeticKeyboard", physicalAlphabeticKeyboard, "ANDROID");
            emit("android.precisionPointer", physicalPrecisionPointer, "ANDROID");
        } catch (Throwable t) {
            emit("android.inputDevices", "UNAVAILABLE: " + shortError(t), "UNAVAILABLE");
        }

        Object buildDesktop = getStaticField("org.chromium.build.BuildConfig",
                "IS_DESKTOP_ANDROID");
        emitValue("BuildConfig.IS_DESKTOP_ANDROID", buildDesktop);

        Object actualDesktop = invokeStatic("org.chromium.base.DeviceInfo",
                "isDesktop", new Class<?>[0], new Object[0]);
        emitValue("DeviceInfo.isDesktop()", actualDesktop);

        Object actualTablet = invokeStatic("org.chromium.ui.base.DeviceFormFactor",
                "isNonMultiDisplayContextOnTablet",
                new Class<?>[] { Context.class }, new Object[] { ctx });
        emitValue("DeviceFormFactor.isNonMultiDisplayContextOnTablet()",
                actualTablet);

        Object actualKeyboard = invokeStatic("org.chromium.ui.base.DeviceInput",
                "supportsAlphabeticKeyboard", new Class<?>[0], new Object[0]);
        emitValue("DeviceInput.supportsAlphabeticKeyboard()", actualKeyboard);

        Object actualPointer = invokeStatic("org.chromium.ui.base.DeviceInput",
                "supportsPrecisionPointer", new Class<?>[0], new Object[0]);
        emitValue("DeviceInput.supportsPrecisionPointer()", actualPointer);

        Object actualDesktopExperience =
                invokeStatic("org.chromium.components.omnibox.OmniboxCapabilities",
                        "hasDesktopExperience",
                        new Class<?>[] { Context.class }, new Object[] { ctx });
        emitValue("OmniboxCapabilities.hasDesktopExperience()", actualDesktopExperience);

        Object actualIsDesktopPlatform =
                invokeStatic("org.chromium.components.omnibox.OmniboxCapabilities",
                        "isDesktopPlatform", new Class<?>[0], new Object[0]);
        emitValue("OmniboxCapabilities.isDesktopPlatform()", actualIsDesktopPlatform);

        Object actualPrecisionExperience =
                invokeStatic("org.chromium.components.omnibox.OmniboxCapabilities",
                        "hasPrecisionPointerExperience",
                        new Class<?>[] { Context.class }, new Object[] { ctx });
        emitValue("OmniboxCapabilities.hasPrecisionPointerExperience()",
                actualPrecisionExperience);

        Object actualAnimations =
                invokeStatic("org.chromium.components.omnibox.OmniboxCapabilities",
                        "areAnimationsEnabled", new Class<?>[0], new Object[0]);
        emitValue("OmniboxCapabilities.areAnimationsEnabled()", actualAnimations);

        // These Chromium classes may be obfuscated in official Chrome APKs.
        // Infer only when all required inputs are directly available. Do not
        // quietly report a source-derived value as an observed method result.
        Boolean desktop = asBoolean(actualDesktop);
        Boolean tablet = asBoolean(actualTablet);
        Boolean keyboard = asBoolean(actualKeyboard);
        Boolean pointer = asBoolean(actualPointer);
        boolean usedAndroidFallback = false;

        // DeviceInput in Chromium 158 takes exactly these values from
        // physical Android InputDevice snapshots. If its R8-renamed class
        // cannot be called, we can still reproduce those inputs directly.
        if (tablet == null && resourceTablet != null) {
            tablet = resourceTablet;
            usedAndroidFallback = true;
        }
        if (tablet == null) {
            // A conservative approximate fallback; Chromium uses a resource
            // bucket, which may differ under custom per-display contexts.
            tablet = config.smallestScreenWidthDp >= 600;
            usedAndroidFallback = true;
            emit("DERIVED.tabletConfigApproximation", tablet,
                    "DERIVED_ANDROID_CONFIGURATION");
        }
        if (keyboard == null && inputDeviceScanSucceeded) {
            keyboard = physicalAlphabeticKeyboard;
            usedAndroidFallback = true;
        }
        if (pointer == null && inputDeviceScanSucceeded) {
            pointer = physicalPrecisionPointer;
            usedAndroidFallback = true;
        }

        if (desktop != null && tablet != null
                && keyboard != null && pointer != null) {
            boolean desktopExperience = desktop || (tablet && keyboard && pointer);
            String evidence = usedAndroidFallback
                    ? "DERIVED_CHROMIUM_158_WITH_ANDROID_FALLBACK"
                    : "DERIVED_CHROMIUM_158";
            emit("DERIVED.hasDesktopExperience", desktopExperience, evidence);
            emit("DERIVED.isDesktopPlatform", desktop, evidence);
            emit("DERIVED.hasPrecisionPointerExperience",
                    desktop || (tablet && pointer), evidence);
            emit("DERIVED.areAnimationsEnabled", !desktop, evidence);
        } else if (!hasPcFeature && tablet != null && keyboard != null
                && pointer != null) {
            // DeviceInfo.isDesktop() in Chromium 158:
            // (IS_DESKTOP_ANDROID && FEATURE_PC) || --force-desktop-android.
            // FEATURE_PC is false, hence the build-time term is provably false.
            // CommandLine is itself R8-hidden, so the forced switch must be
            // checked separately. Do NOT claim the condition as a direct value.
            String assumed = "CONDITIONAL_NO_FORCE_DESKTOP_SWITCH";
            emit("DERIVED.DeviceInfo.isDesktop()",
                    "false IF --force-desktop-android is absent", assumed);
            emit("DERIVED.hasDesktopExperience",
                    (tablet && keyboard && pointer)
                    + " IF --force-desktop-android is absent", assumed);
            emit("DERIVED.isDesktopPlatform",
                    "false IF --force-desktop-android is absent", assumed);
            emit("DERIVED.hasPrecisionPointerExperience",
                    (tablet && pointer)
                    + " IF --force-desktop-android is absent", assumed);
            emit("DERIVED.areAnimationsEnabled",
                    "true IF --force-desktop-android is absent", assumed);
        } else {
            emit("DERIVED.hasDesktopExperience",
                    "UNKNOWN: DeviceInfo.isDesktop or input evidence inaccessible",
                    "UNAVAILABLE");
        }

        // Diagnostic only. The following defaults may be compiled differently
        // in Desktop Chrome; isEnabled() reports actual cached feature states
        // when the original symbols survive R8.
        reportFlag("sEnableAndroidSidePanel");
        reportFlag("sUseWebUiNtpAndroid");
        reportFlag("sWebUiAndroidTheming");
        reportFlag("sChromeNativeUrlOverriding");

        emit("SNAPSHOT_END", "index=" + snapshot
                + " (DIRECT differs from DERIVED)", "DIRECT");
    }

    private Boolean getTabletBucket(Context ctx) {
        try {
            Resources resources = ctx.getResources();
            int id = resources.getIdentifier(
                    "min_screen_width_bucket", "integer", CHROME);
            if (id == 0) return null;
            return resources.getInteger(id) >= 2;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void reportFlag(String fieldName) {
        Object flag = getStaticField(
                "org.chromium.chrome.browser.flags.ChromeFeatureList", fieldName);
        if (flag == null) {
            emit("ChromeFeatureList." + fieldName, "UNAVAILABLE",
                    "UNAVAILABLE");
            return;
        }
        try {
            Method method = flag.getClass().getMethod("isEnabled");
            method.setAccessible(true);
            Object enabled = method.invoke(flag);
            emit("ChromeFeatureList." + fieldName, enabled, "DIRECT");
        } catch (Throwable t) {
            emit("ChromeFeatureList." + fieldName,
                    "UNAVAILABLE: " + shortError(t), "UNAVAILABLE");
        }
    }

    private static Boolean asBoolean(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private void emitValue(String key, Object value) {
        emit(key, value == null ? "UNAVAILABLE (R8 or inaccessible)" : value,
                value == null ? "UNAVAILABLE" : "DIRECT");
    }

    private Object getStaticField(String className, String fieldName) {
        try {
            Class<?> cls = findClass(className);
            Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable error) {
            reportReflectionFailure(className + "." + fieldName, error);
            return null;
        }
    }

    private Object invokeStatic(String className, String name,
            Class<?>[] parameterTypes, Object[] arguments) {
        try {
            Class<?> cls = findClass(className);
            Method method = cls.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (Throwable error) {
            reportReflectionFailure(className + "." + name + "()", error);
            return null;
        }
    }

    private void reportReflectionFailure(String method, Throwable error) {
        // Report once: previously every reflection failure was swallowed,
        // making an R8 class rename indistinguishable from a method mismatch.
        if (reports.get() != 1) return;
        Throwable cause = error;
        if (error instanceof java.lang.reflect.InvocationTargetException
                && error.getCause() != null) cause = error.getCause();
        emit("REFLECTION_FAILURE " + method, shortError(cause), "DIAGNOSTIC");
    }

    private Class<?> findClass(String name) throws ClassNotFoundException {
        ClassLoader[] loaders = {
                chromeLoader,
                chromeContext == null ? null : chromeContext.getClassLoader(),
                Thread.currentThread().getContextClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ignored) {
                // Other split APK ClassLoaders may hold this class.
            }
        }
        throw new ClassNotFoundException(name);
    }

    private static String shortError(Throwable error) {
        String simple = error.getClass().getSimpleName();
        String message = error.getMessage();
        return simple + (message == null ? "" : ": " + message);
    }

    private void emit(String key, Object value, String evidence) {
        Log.i(TAG, "CHROME_STATE [" + evidence + "] " + key + "=" + value);
    }
}
