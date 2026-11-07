package com.atuy.desktopchromeinit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.atuy.desktopchromeinit.compat.XC_MethodHook;
import com.atuy.desktopchromeinit.compat.XposedBridge;

import java.lang.reflect.Method;

/**
 * Narrow Omnibox compatibility fixes for Chrome Desktop Android APKs
 * installed on phones that do not advertise android.hardware.type.pc.
 *
 * Do not spoof DeviceInfo.isDesktop() globally: doing so changes many
 * unrelated Chrome features and can trigger additional desktop-only paths.
 */
final class ChromeInputCompatibility {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET = "com.android.chrome";
    private static final String PC_FEATURE = "android.hardware.type.pc";
    private static volatile boolean desktopExperienceHooked;
    private static volatile boolean keyboardDebounceHooked;

    private ChromeInputCompatibility() {}

    static synchronized void install(ClassLoader loader) {
        if (loader == null) return;
        if (!desktopExperienceHooked) {
            try {
                Class<?> cls = Class.forName(
                        "org.chromium.components.omnibox.OmniboxCapabilities",
                        false, loader);
                Method method = cls.getDeclaredMethod("hasDesktopExperience", Context.class);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1
                                || !(param.args[0] instanceof Context)) return;
                        Context context = (Context) param.args[0];
                        if (!shouldApplyFix(context)) return;
                        // Retain the current URL when tapping the Omnibox. This
                        // affects only Omnibox desktop-experience checks, not
                        // Chrome's OS-wide DeviceInfo.isDesktop() result.
                        param.setResult(true);
                    }
                });
                desktopExperienceHooked = true;
                log("Omnibox desktop-experience compatibility hook installed");
            } catch (Throwable t) {
                log("Omnibox desktop-experience hook unavailable: " + t);
            }
        }

        if (!keyboardDebounceHooked) {
            try {
                Class<?> cls = Class.forName(
                        "org.chromium.components.omnibox.OmniboxFeatures",
                        false, loader);
                Method method = cls.getDeclaredMethod("isDebounceKeyboardVisibilityEnabled");
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = ChromeInitHook.getCurrentChromeApplicationForInputFix();
                        if (!shouldApplyFix(context)) return;
                        // Use actual IME requests instead of relying on the
                        // 150-ms SHOWN/HIDDEN state machine. In unsupported
                        // desktop-on-phone setups that state can go stale when
                        // the IME/window focus moves unexpectedly.
                        param.setResult(false);
                    }
                });
                keyboardDebounceHooked = true;
                log("Omnibox IME debounce compatibility hook installed");
            } catch (Throwable t) {
                log("Omnibox IME debounce hook unavailable: " + t);
            }
        }
    }

    private static boolean shouldApplyFix(Context context) {
        if (context == null || !TARGET.equals(context.getPackageName())) return false;
        try {
            PackageManager pm = context.getPackageManager();
            if (pm.hasSystemFeature(PC_FEATURE)) return false;
            // Only act on the Desktop Chrome variant containing the Extensions
            // app menu entry. Avoid modifying ordinary mobile Chrome.
            return context.getResources().getIdentifier(
                    "extensions_menu_menu_id", "id", TARGET) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
