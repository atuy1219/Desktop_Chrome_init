package com.atuy.chromemobilecompat;

import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import com.atuy.chromemobilecompat.compat.XposedBridge;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Read-only native bootstrap. Native discovery never gates extension setup. */
final class ChromeNativeProbe {
    private static final String LIB = "libchrome_omnibox_probe.so";
    private static boolean loaded, scheduled;
    private static int attempts;

    static synchronized void start(ApplicationInfo module, String phase) {
        if (loaded && scheduled) return;
        if (attempts >= 3 && !loaded) return;
        attempts++;
        marker("entry phase=" + phase + " attempt=" + attempts);
        if (!loaded) {
            try {
                System.loadLibrary("chrome_omnibox_probe");
                loaded = true;
                marker("loadLibrary OK");
            } catch (Throwable failure) {
                marker("loadLibrary failed: " + failure.getClass().getSimpleName());
            }
        }
        if (!loaded && module != null) {
            Set<String> candidates = new LinkedHashSet<>();
            if (module.nativeLibraryDir != null) {
                File file = new File(module.nativeLibraryDir, LIB);
                if (file.isFile()) candidates.add(file.getAbsolutePath());
            }
            addApk(candidates, module.sourceDir);
            if (module.splitSourceDirs != null) {
                for (String apk : module.splitSourceDirs) addApk(candidates, apk);
            }
            for (String candidate : candidates) {
                try {
                    System.load(candidate);
                    loaded = true;
                    marker("explicit load OK");
                    break;
                } catch (Throwable failure) {
                    marker("explicit load failed: " + failure.getClass().getSimpleName()
                            + " " + failure.getMessage());
                }
            }
            if (!loaded) marker("no load succeeded candidates=" + candidates.size());
        }
        if (!loaded || scheduled) return;
        scheduled = true;
        Handler main = new Handler(Looper.getMainLooper());
        // Bounded snapshots: no persistent polling, no clipboard or page access.
        long[] delays = {0L, 1000L, 4000L, 12000L};
        for (int i = 0; i < delays.length; i++) {
            final int index = i;
            main.postDelayed(() -> {
                try {
                    String report = nativeSnapshot(index);
                    if (report != null) {
                        for (String line : report.split("\\n")) {
                            if (!line.isEmpty()) marker(line);
                        }
                    }
                    marker("JNI snapshot OK index=" + index);
                } catch (Throwable failure) {
                    marker("JNI snapshot failed: " + failure.getClass().getSimpleName()
                            + " " + failure.getMessage());
                }
            }, delays[i]);
        }
    }

    private static void addApk(Set<String> candidates, String path) {
        if (path == null) return;
        String member = "lib/arm64-v8a/" + LIB;
        try (ZipFile zip = new ZipFile(path)) {
            if (zip.getEntry(member) != null) candidates.add(path + "!/" + member);
        } catch (Exception ignored) {}
    }

    private static void marker(String message) {
        XposedBridge.log("NATIVE_PROBE " + message);
    }

    private static native String nativeSnapshot(int phase);
    private ChromeNativeProbe() {}
}
