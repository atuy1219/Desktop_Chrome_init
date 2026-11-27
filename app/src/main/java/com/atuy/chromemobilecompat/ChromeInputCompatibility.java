package com.atuy.chromemobilecompat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.util.Log;

import com.atuy.chromemobilecompat.compat.XC_MethodHook;
import com.atuy.chromemobilecompat.compat.XposedBridge;

import java.lang.reflect.Method;

/**
 * Narrow Omnibox compatibility fixes for Chrome Desktop Android APKs
 * installed on phones that do not advertise android.hardware.type.pc.
 *
 * Do not spoof DeviceInfo.isDesktop() globally: doing so changes many
 * unrelated Chrome features and can trigger additional desktop-only paths.
 */
final class ChromeInputCompatibility {
    private static final String TAG = "ChromeMobileCompat";
    private static final String TARGET = "com.android.chrome";
    private static final String PC_FEATURE = "android.hardware.type.pc";
    private static volatile boolean desktopExperienceHooked;
    private static volatile boolean editUrlProcessorHooked;
    private static final ThreadLocal<Integer> EDIT_URL_DEPTH = new ThreadLocal<>();
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
                        if (!shouldUseMobileOmnibox(context)) return;
                        // Preserve the established desktop focus flow. Only the
                        // current-page processor may opt into mobile presentation.
                        // Native zero-prefix match generation is independent.
                        Integer depth = EDIT_URL_DEPTH.get();
                        param.setResult(depth == null || depth == 0);
                    }
                });
                desktopExperienceHooked = true;
                log("Omnibox focus compatibility hook installed");
            } catch (Throwable t) {
                log("Omnibox focus compatibility hook unavailable: " + t);
            }
        }

        if (desktopExperienceHooked && !editUrlProcessorHooked) {
            try {
                Class<?> processor = Class.forName(
                        "org.chromium.chrome.browser.omnibox.suggestions.editurl.EditUrlSuggestionProcessor",
                        false, loader);
                Class<?> match = Class.forName(
                        "org.chromium.components.omnibox.AutocompleteMatch", false, loader);
                Method method = processor.getDeclaredMethod(
                        "doesProcessSuggestion", match, int.class);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Integer depth = EDIT_URL_DEPTH.get();
                        EDIT_URL_DEPTH.set(depth == null ? 1 : depth + 1);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Integer depth = EDIT_URL_DEPTH.get();
                        if (depth == null || depth <= 1) EDIT_URL_DEPTH.remove();
                        else EDIT_URL_DEPTH.set(depth - 1);
                    }
                });
                editUrlProcessorHooked = true;
                log("Scoped mobile EditUrlSuggestionProcessor hook installed");
            } catch (Throwable t) {
                log("Scoped edit URL processor unavailable (native behavior retained): " + t);
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

    private static boolean shouldUseMobileOmnibox(Context context) {
        if (!shouldApplyFix(context)) return false;
        // Leave genuine tablet/desktop configurations to Chromium.
        Configuration config = context.getResources().getConfiguration();
        int smallestWidth = config.smallestScreenWidthDp;
        return smallestWidth > 0 && smallestWidth < 600;
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
