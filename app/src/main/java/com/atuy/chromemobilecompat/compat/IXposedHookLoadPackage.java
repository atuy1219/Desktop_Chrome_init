package com.atuy.chromemobilecompat.compat;

import com.atuy.chromemobilecompat.compat.callbacks.XC_LoadPackage;

public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam)
            throws Throwable;
}
