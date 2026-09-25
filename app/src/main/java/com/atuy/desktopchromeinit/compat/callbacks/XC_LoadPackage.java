package com.atuy.desktopchromeinit.compat.callbacks;

import android.content.pm.ApplicationInfo;

public final class XC_LoadPackage {
    private XC_LoadPackage() {}

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
    }
}
