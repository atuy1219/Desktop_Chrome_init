package com.atuy.desktopchromeinit.compat.callbacks;

/**
 * Minimal load-package parameter used internally by DesktopChromeInit.
 */
public final class XC_LoadPackage {
    private XC_LoadPackage() {}

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}
