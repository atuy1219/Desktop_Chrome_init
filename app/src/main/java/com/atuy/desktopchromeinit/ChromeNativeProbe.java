package com.atuy.desktopchromeinit;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
    private static boolean loaded;
    private static int loadAttempts;
    private static boolean snapshotsScheduled;

    static synchronized void start(String phase) {
        if (!loaded) {
            loadAttempts++;
            Log.i(TAG, "Java bootstrap phase=" + phase
                    + " attempt=" + loadAttempts
                    + " loader=" + ChromeNativeProbe.class.getClassLoader()
                    + " loading=chrome_omnibox_probe");
            try {
                System.loadLibrary("chrome_omnibox_probe");
                loaded = true;
                Log.i(TAG, "Java System.loadLibrary SUCCESS phase=" + phase
                        + " (native_init callback status checked separately)");
            } catch (Throwable failure) {
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

    private static void snapshot(String phase, int index) {
        try {
            Log.i(TAG, "Java ELF snapshot requested phase=" + phase);
            nativeSnapshot(index);
        } catch (Throwable failure) {
            Log.e(TAG, "Java ELF snapshot FAILED phase=" + phase
                    + " exception=" + failure.getClass().getName()
                    + ": " + failure.getMessage(), failure);
        }
    }

    private static native void nativeSnapshot(int phase);

    private ChromeNativeProbe() {}
}
