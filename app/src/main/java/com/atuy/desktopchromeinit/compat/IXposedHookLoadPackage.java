package com.atuy.desktopchromeinit.compat;

import com.atuy.desktopchromeinit.compat.callbacks.XC_LoadPackage;

/**
 * Minimal source-compatibility interface for the existing hook implementation.
 * This is an internal module API and is not the legacy Xposed framework API.
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam)
            throws Throwable;
}
