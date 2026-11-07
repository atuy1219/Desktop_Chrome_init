package com.atuy.desktopchromeinit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explicit module-library bootstrap for native Chrome diagnostics.
 *
 * native_init.list advertises the LSPosed callback, but does not guarantee
 * the module .so has been loaded. System.loadLibrary must be attempted from
 * this class's module classloader. A JNI snapshot independently enumerates
 * mapped ELF libraries if Vector never invokes native_init().
 *
 * This is diagnostic-only: no executable memory or Chromium objects modified.
 */
final class ChromeNativeProbe {
    private static final String TAG = "ChromeNativeOmnibox";
    private static final String BRIDGE_TAG = "DesktopChromeInit";
    private static final String VERSION = "0.4.27";
    private static int explicitLoads;
    private static boolean loaded;
    private static int loadAttempts;
    private static boolean snapshotsScheduled;

    static synchronized void start(String phase) {
        Log.e(BRIDGE_TAG, "NATIVE_PROBE " + VERSION + " entered phase=" + phase
                + " loaded=" + loaded + " attempts=" + loadAttempts);
        if (!loaded) {
            loadAttempts++;
            Log.i(TAG, "Java bootstrap phase=" + phase
                    + " attempt=" + loadAttempts
                    + " loader=" + ChromeNativeProbe.class.getClassLoader()
                    + " loading=chrome_omnibox_probe");
            try {
                System.loadLibrary("chrome_omnibox_probe");
                loaded = true;
                Log.e(BRIDGE_TAG, "NATIVE_PROBE System.loadLibrary OK phase=" + phase);
                Log.i(TAG, "Java System.loadLibrary SUCCESS phase=" + phase
                        + " (native_init callback status checked separately)");
            } catch (Throwable failure) {
                Log.e(BRIDGE_TAG, "NATIVE_PROBE System.loadLibrary FAILED phase="
                        + phase + " type=" + failure.getClass().getSimpleName()
                        + " message=" + failure.getMessage());
                Log.e(TAG, "Java System.loadLibrary FAILED phase=" + phase
                        + " exception=" + failure.getClass().getName()
                        + ": " + failure.getMessage(), failure);
                return;
            }
        }
        if (snapshotsScheduled) return;
        snapshotsScheduled = true;
        Handler main = new Handler(Looper.getMainLooper());
        snapshot("initial", 0);
        main.postDelayed(() -> snapshot("after-3s", 1), 3000L);
        main.postDelayed(() -> snapshot("after-10s", 2), 10000L);
    }

    /**
     * Fallback for Xposed runtimes whose module class loader has no native
     * library search path. Android can load either an extracted module ELF
     * or an uncompressed page-aligned ELF inside its installed APK.
     *
     * No Chrome APK is modified. No unknown filesystem paths are searched.
     * The PackageManager lookup may be disallowed by package visibility;
     * the module-classloader path gives an independent fallback.
     */
    static synchronized void startWithContext(Context chromeContext, String phase) {
        start(phase);
        if (loaded || chromeContext == null) return;

        Set<String> candidates = new LinkedHashSet<>();
        try {
            ApplicationInfo info = chromeContext.getPackageManager()
                    .getApplicationInfo("com.atuy.desktopchromeinit", 0);
            if (info.nativeLibraryDir != null) {
                File extracted = new File(info.nativeLibraryDir,
                        "libchrome_omnibox_probe.so");
                if (extracted.isFile()) candidates.add(extracted.getAbsolutePath());
            }
            addApkCandidate(candidates, info.sourceDir);
            if (info.splitSourceDirs != null) {
                for (String split : info.splitSourceDirs) {
                    addApkCandidate(candidates, split);
                }
            }
        } catch (Throwable failure) {
            Log.e(BRIDGE_TAG, "NATIVE_PROBE package lookup unavailable type="
                    + failure.getClass().getSimpleName());
        }

        // The module's class loader itself may retain the actual APK location.
        // This is bounded parsing of its printed source path, not scanning
        // Chrome's private directories or arbitrary APK files.
        try {
            String desc = String.valueOf(ChromeNativeProbe.class.getClassLoader());
            Matcher matcher = Pattern.compile("zip file \\"([^\\"]+\\.apk)\\"")
                    .matcher(desc);
            while (matcher.find()) {
                String path = matcher.group(1);
                if (path != null && path.contains("com.atuy.desktopchromeinit")) {
                    addApkCandidate(candidates, path);
                }
            }
        } catch (Throwable ignored) {}

        for (String candidate : candidates) {
            explicitLoads++;
            Log.e(BRIDGE_TAG, "NATIVE_PROBE absolute load attempt="
                    + explicitLoads + " phase=" + phase);
            try {
                System.load(candidate);
                loaded = true;
                Log.e(BRIDGE_TAG, "NATIVE_PROBE absolute load SUCCESS phase="
                        + phase + " attempt=" + explicitLoads);
                break;
            } catch (Throwable failure) {
                Log.e(BRIDGE_TAG, "NATIVE_PROBE absolute load FAILED phase="
                        + phase + " attempt=" + explicitLoads + " type="
                        + failure.getClass().getSimpleName() + " message="
                        + failure.getMessage());
            }
        }
        if (!loaded) {
            Log.e(BRIDGE_TAG, "NATIVE_PROBE no load candidate succeeded"
                    + " candidates=" + candidates.size());
            return;
        }
        start("absolute-loaded:" + phase);
    }

    private static void addApkCandidate(Set<String> candidates, String path) {
        if (path == null || !path.endsWith(".apk")) return;
        candidates.add(path + "!/lib/arm64-v8a/libchrome_omnibox_probe.so");
    }

    private static void snapshot(String phase, int index) {
        try {
            Log.e(BRIDGE_TAG, "NATIVE_PROBE JNI snapshot BEGIN phase=" + phase);
            Log.i(TAG, "Java ELF snapshot requested phase=" + phase);
            nativeSnapshot(index);
            Log.e(BRIDGE_TAG, "NATIVE_PROBE JNI snapshot OK phase=" + phase);
        } catch (Throwable failure) {
            Log.e(BRIDGE_TAG, "NATIVE_PROBE JNI snapshot FAILED phase=" + phase
                    + " type=" + failure.getClass().getSimpleName()
                    + " message=" + failure.getMessage());
            Log.e(TAG, "Java ELF snapshot FAILED phase=" + phase
                    + " exception=" + failure.getClass().getName()
                    + ": " + failure.getMessage(), failure);
        }
    }

    private static native void nativeSnapshot(int phase);

    private ChromeNativeProbe() {}
}
