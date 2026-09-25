package com.atuy.desktopchromeinit;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atuy.desktopchromeinit.compat.XposedBridge;
import com.atuy.desktopchromeinit.compat.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Modern libxposed API 102 entry point.
 *
 * Installation is retried because Chrome browser classes may arrive through
 * split APK class loaders at different lifecycle phases. No obfuscated Chrome
 * class name is used as a readiness probe.
 */
public final class ModernEntry extends XposedModule {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET_PACKAGE = "com.android.chrome";
    private static final long RETRY_INTERVAL_MS = 250L;
    private static final int MAX_RETRY_COUNT = 240;

    private volatile String processName;
    private volatile ClassLoader latestClassLoader;
    private volatile boolean installed;
    private volatile boolean retryScheduled;
    private int retryCount;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.attach(this);
        d("onModuleLoaded process=" + processName
                + " api=" + getApiVersion());

        if (TARGET_PACKAGE.equals(processName)) {
            scheduleRetry(0L);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!isTarget(param.getPackageName(), param.isFirstPackage())) {
            return;
        }

        latestClassLoader = param.getDefaultClassLoader();
        d("onPackageLoaded loader=" + latestClassLoader);
        tryInstall("onPackageLoaded");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!isTarget(param.getPackageName(), param.isFirstPackage())) {
            return;
        }

        latestClassLoader = param.getClassLoader();
        d("onPackageReady loader=" + latestClassLoader);
        tryInstall("onPackageReady");
    }

    private boolean isTarget(String packageName, boolean firstPackage) {
        return firstPackage
                && TARGET_PACKAGE.equals(packageName)
                && TARGET_PACKAGE.equals(processName);
    }

    private void scheduleRetry(long delayMs) {
        if (installed || retryScheduled) {
            return;
        }

        retryScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            retryScheduled = false;
            if (installed) {
                return;
            }

            retryCount++;
            tryInstall("poll#" + retryCount);

            if (!installed && retryCount < MAX_RETRY_COUNT) {
                scheduleRetry(RETRY_INTERVAL_MS);
            } else if (!installed) {
                d("gave up installing Chrome hooks after "
                        + retryCount + " retries");
            }
        }, delayMs);
    }

    private synchronized void tryInstall(String phase) {
        if (installed || !TARGET_PACKAGE.equals(processName)) {
            return;
        }

        XposedBridge.attach(this);

        ClassLoader loader = resolveUsableClassLoader();
        if (loader == null) {
            d(phase + ": ChromeTabbedActivity not loadable yet");
            scheduleRetry(RETRY_INTERVAL_MS);
            return;
        }

        Application app = currentApplication();

        XC_LoadPackage.LoadPackageParam compat =
                new XC_LoadPackage.LoadPackageParam();
        compat.packageName = TARGET_PACKAGE;
        compat.processName = processName;
        compat.classLoader = loader;
        compat.appInfo = app != null ? app.getApplicationInfo() : null;

        try {
            d(phase + ": attempting generic hook install"
                    + " loader=" + loader
                    + " appInfo=" + (compat.appInfo != null));
            new ChromeInitHook().handleLoadPackage(compat);

            if (ChromeInitHook.isExtensionsMenuGuardInstalled()) {
                installed = true;
                latestClassLoader = loader;
                d(phase + ": generic Extensions crash guard confirmed");
            } else {
                d(phase + ": crash guard not installed yet; retrying");
                scheduleRetry(RETRY_INTERVAL_MS);
            }
        } catch (Throwable t) {
            d(phase + ": hook installation threw " + t);
            log(Log.ERROR, TAG, phase + ": hook installation failed", t);
            scheduleRetry(RETRY_INTERVAL_MS);
        }
    }

    private ClassLoader resolveUsableClassLoader() {
        ClassLoader loader = latestClassLoader;
        if (canLoadChromeActivity(loader)) {
            return loader;
        }

        Application app = currentApplication();
        if (app != null) {
            loader = app.getClassLoader();
            if (canLoadChromeActivity(loader)) {
                latestClassLoader = loader;
                return loader;
            }
        }

        loader = Thread.currentThread().getContextClassLoader();
        if (canLoadChromeActivity(loader)) {
            latestClassLoader = loader;
            return loader;
        }

        return null;
    }

    private static boolean canLoadChromeActivity(ClassLoader loader) {
        if (loader == null) {
            return false;
        }

        try {
            Class.forName(
                    "org.chromium.chrome.browser.ChromeTabbedActivity",
                    false,
                    loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread =
                    Class.forName("android.app.ActivityThread");
            Method currentApplication =
                    activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            return value instanceof Application
                    ? (Application) value
                    : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void d(String message) {
        Log.i(TAG, message);
        try {
            log(Log.INFO, TAG, message);
        } catch (Throwable ignored) {
        }
    }
}
