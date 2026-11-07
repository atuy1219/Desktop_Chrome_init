package com.atuy.desktopchromeinit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

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
    private static volatile boolean urlBarTraceHooked;
    private static final Map<TextView, Boolean> TRACKED_URL_BARS =
            Collections.synchronizedMap(new WeakHashMap<>());

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

        installUrlBarTrace(loader);
    }

    /**
     * Diagnosis only. Track the actual text-view transitions instead of
     * assuming that a desktop-capability or keyboard-debounce flag controls
     * URL persistence. Never log the URL, typed content or suggestion text.
     *
     * UrlBar.onFocusChanged() overrides an Android framework method, so its
     * signature is stable even if other Omnibox internals change under R8.
     */
    private static void installUrlBarTrace(ClassLoader loader) {
        if (urlBarTraceHooked) return;
        try {
            Class<?> cls = Class.forName(
                    "org.chromium.chrome.browser.omnibox.UrlBar", false, loader);
            Method method = cls.getDeclaredMethod(
                    "onFocusChanged", boolean.class, int.class, Rect.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof TextView)) return;
                    TextView urlBar = (TextView) param.thisObject;
                    attachTextTrace(urlBar);
                    logUrlBarState("focus-before", urlBar);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof TextView)) return;
                    TextView urlBar = (TextView) param.thisObject;
                    logUrlBarState("focus-after", urlBar);
                    urlBar.post(() -> logUrlBarState("focus-next-frame", urlBar));
                    urlBar.postDelayed(
                            () -> logUrlBarState("focus-after-200ms", urlBar), 200L);
                }
            });
            urlBarTraceHooked = true;
            log("Omnibox UrlBar focus/length trace installed (no URL text collected)");
        } catch (Throwable t) {
            log("Omnibox UrlBar focus trace unavailable: " + t);
        }
    }

    private static void attachTextTrace(TextView view) {
        synchronized (TRACKED_URL_BARS) {
            if (TRACKED_URL_BARS.containsKey(view)) return;
            TRACKED_URL_BARS.put(view, Boolean.TRUE);
        }
        view.addTextChangedListener(new TextWatcher() {
            int previousLength;

            @Override
            public void beforeTextChanged(
                    CharSequence text, int start, int count, int after) {
                previousLength = text == null ? 0 : text.length();
            }

            @Override
            public void onTextChanged(
                    CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                int currentLength = text == null ? 0 : text.length();
                log("URLBAR text length=" + previousLength + "->" + currentLength
                        + " focused=" + view.hasFocus()
                        + " shown=" + view.isShown());
            }
        });
    }

    private static void logUrlBarState(String event, TextView view) {
        try {
            int length = view.getText() == null ? 0 : view.getText().length();
            WindowInsets insets = view.getRootWindowInsets();
            String ime = insets == null ? "unknown"
                    : Boolean.toString(insets.isVisible(WindowInsets.Type.ime()));
            log("URLBAR " + event
                    + " length=" + length
                    + " focused=" + view.hasFocus()
                    + " windowFocus=" + view.hasWindowFocus()
                    + " shown=" + view.isShown()
                    + " imeVisible=" + ime);
        } catch (Throwable t) {
            log("URLBAR trace failed: " + t.getClass().getSimpleName());
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
