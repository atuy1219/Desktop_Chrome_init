package com.atuy.desktopchromeinit;

import android.util.Log;

import com.atuy.desktopchromeinit.compat.XposedBridge;
import com.atuy.desktopchromeinit.compat.callbacks.XC_LoadPackage;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Modern libxposed API 102 entry point.
 *
 * The actual Chrome repair remains in ChromeInitHook. This entry adapts the
 * modern package lifecycle to the small compatibility facade used by that
 * implementation, without depending on the legacy Xposed API or a legacy
 * bridge in the framework.
 */
public final class ModernEntry extends XposedModule {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET_PACKAGE = "com.android.chrome";

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
        if (installed
                || !param.isFirstPackage()
                || !TARGET_PACKAGE.equals(param.getPackageName())
                || !TARGET_PACKAGE.equals(processName)) {
            return;
        }

        installed = true;
        XposedBridge.attach(this);

        XC_LoadPackage.LoadPackageParam compat =
                new XC_LoadPackage.LoadPackageParam();
        compat.packageName = param.getPackageName();
        compat.processName = processName;
        compat.classLoader = param.getDefaultClassLoader();

        try {
            new ChromeInitHook().handleLoadPackage(compat);
        } catch (Throwable t) {
            installed = false;
            log(Log.ERROR, TAG, "hook installation failed", t);
        }
    }
}
