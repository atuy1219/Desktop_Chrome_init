package com.atuy.desktopchromeinit;

import android.util.Log;

import com.atuy.desktopchromeinit.compat.XposedBridge;
import com.atuy.desktopchromeinit.compat.callbacks.XC_LoadPackage;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * Modern libxposed API 102 entry point.
 *
 * Chrome Desktop keeps most browser code in split APKs. onPackageLoaded() can
 * therefore arrive before ChromeTabbedActivity is visible from the default
 * ClassLoader. Try there for the earliest possible install, but always retry at
 * onPackageReady(), where libxposed exposes the final AppComponentFactory
 * ClassLoader.
 */
public final class ModernEntry extends XposedModule {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET_PACKAGE = "com.android.chrome";
    private static final String CHROME_TABBED_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity";

    private volatile String processName;
    private volatile boolean installed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.attach(this);
        log(Log.INFO, TAG,
                "modern API " + getApiVersion() + " loaded in " + processName);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!isTarget(param.getPackageName(), param.isFirstPackage())) {
            return;
        }
        tryInstall(
                param.getPackageName(),
                param.getDefaultClassLoader(),
                "onPackageLoaded");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!isTarget(param.getPackageName(), param.isFirstPackage())) {
            return;
        }
        tryInstall(
                param.getPackageName(),
                param.getClassLoader(),
                "onPackageReady");
    }

    private boolean isTarget(String packageName, boolean firstPackage) {
        return firstPackage
                && TARGET_PACKAGE.equals(packageName)
                && TARGET_PACKAGE.equals(processName);
    }

    private synchronized void tryInstall(
            String packageName,
            ClassLoader classLoader,
            String phase) {

        if (installed) {
            return;
        }

        XposedBridge.attach(this);

        if (classLoader == null) {
            log(Log.WARN, TAG, phase + ": ClassLoader is null; deferring");
            return;
        }

        try {
            Class.forName(CHROME_TABBED_ACTIVITY, false, classLoader);
        } catch (Throwable t) {
            log(Log.INFO, TAG,
                    phase + ": ChromeTabbedActivity not visible yet; deferring");
            return;
        }

        XC_LoadPackage.LoadPackageParam compat =
                new XC_LoadPackage.LoadPackageParam();
        compat.packageName = packageName;
        compat.processName = processName;
        compat.classLoader = classLoader;

        try {
            log(Log.INFO, TAG,
                    phase + ": Chrome classes ready; installing hooks");
            new ChromeInitHook().handleLoadPackage(compat);
            installed = true;
            log(Log.INFO, TAG,
                    phase + ": hook installation completed");
        } catch (Throwable t) {
            installed = false;
            log(Log.ERROR, TAG,
                    phase + ": hook installation failed", t);
        }
    }
}
