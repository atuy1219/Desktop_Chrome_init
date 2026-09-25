package com.atuy.desktopchromeinit.compat;

import com.atuy.desktopchromeinit.compat.callbacks.XC_LoadPackage;

public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam)
            throws Throwable;
}
