package com.atuy.desktopchromeinit;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.TextView;

import com.atuy.desktopchromeinit.compat.XC_MethodHook;
import com.atuy.desktopchromeinit.compat.XposedBridge;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chrome URL-bar compatibility on unsupported phone layouts.
 *
 * Chromium release APKs obfuscate the Omnibox implementation. Intercept
 * Android's stable TextView focus callback and identify the URL field by
 * Chrome's stable resource id, rather than depending on any internal
 * Chromium Java class or method name.
 *
 * This is intentionally a conservative visual/editor-state workaround, not
 * a replacement for Chromium's FuseboxSessionState implementation.
 */
final class ChromeInputCompatibility {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET = "com.android.chrome";
    private static final String PC_FEATURE = "android.hardware.type.pc";

    // Chromium 158 on phones may clear its own URL editor immediately after
    // focus when the desktop initial-input retention branch is unavailable.
    // Human-initiated text deletion and navigation must never be overridden.
    private static final long RESTORE_WINDOW_MS = 160L;
    private static volatile boolean urlBarHooked;

    private static final Map<TextView, Boolean> TRACKED_URL_BARS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<TextView, FocusSnapshot> FOCUS_SNAPSHOTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ChromeInputCompatibility() {}

    private static final class FocusSnapshot {
        final String originalText;
        final long beganAt;
        boolean scheduled;
        boolean consumed;

        FocusSnapshot(String text) {
            originalText = text;
            beganAt = SystemClock.uptimeMillis();
        }
    }

    static synchronized void install(ClassLoader loader) {
        if (loader == null || urlBarHooked) return;
        try {
            Method focus = TextView.class.getDeclaredMethod(
                    "onFocusChanged", boolean.class, int.class, Rect.class);
            XposedBridge.hookMethod(focus, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof TextView)) return;
                    TextView urlBar = (TextView) param.thisObject;
                    if (!isChromeUrlBar(urlBar)) return;
                    attachTextTrace(urlBar);

                    if (param.args != null && param.args.length > 0
                            && Boolean.TRUE.equals(param.args[0])) {
                        captureFocusedUrl(urlBar);
                    } else {
                        FOCUS_SNAPSHOTS.remove(urlBar);
                    }
                    logUrlBarState("focus-before", urlBar);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof TextView)) return;
                    TextView urlBar = (TextView) param.thisObject;
                    if (!isChromeUrlBar(urlBar)) return;
                    logUrlBarState("focus-after", urlBar);
                    urlBar.post(() -> logUrlBarState("focus-next-frame", urlBar));
                    urlBar.postDelayed(
                            () -> logUrlBarState("focus-after-200ms", urlBar), 200L);
                }
            });
            urlBarHooked = true;
            log("Omnibox URLBAR resource-scoped focus/recovery hook installed");
        } catch (Throwable t) {
            log("Omnibox URLBAR resource-scoped focus hook unavailable: " + t);
        }
    }

    private static void captureFocusedUrl(TextView view) {
        try {
            // Only on the unsupported-phone Desktop Chrome variant. Do not
            // affect ordinary Chrome or a device with PC input capabilities.
            if (!shouldApplyFix(view.getContext())) {
                FOCUS_SNAPSHOTS.remove(view);
                return;
            }
            CharSequence current = view.getText();
            if (current == null || current.length() == 0) {
                FOCUS_SNAPSHOTS.remove(view);
                return;
            }
            String text = current.toString();
            // An ellipsized or otherwise truncated display string must not
            // be written back as a navigable URL.
            if (text.indexOf('\u2026') >= 0) {
                FOCUS_SNAPSHOTS.remove(view);
                log("URLBAR recovery skipped: ellipsized display text");
                return;
            }
            FOCUS_SNAPSHOTS.put(view, new FocusSnapshot(text));
            log("URLBAR recovery armed length=" + text.length());
        } catch (Throwable t) {
            FOCUS_SNAPSHOTS.remove(view);
            log("URLBAR recovery capture failed: " + t.getClass().getSimpleName());
        }
    }

    private static boolean isChromeUrlBar(TextView view) {
        if (view.getId() == View.NO_ID) return false;
        try {
            Context context = view.getContext();
            if (context == null || !TARGET.equals(context.getPackageName())) return false;
            int id = context.getResources().getIdentifier("url_bar", "id", TARGET);
            return id != 0 && view.getId() == id;
        } catch (Throwable t) {
            return false;
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

                if (previousLength <= 0 || currentLength != 0 || !view.hasFocus()) {
                    return;
                }

                FocusSnapshot snapshot = FOCUS_SNAPSHOTS.get(view);
                if (snapshot == null || snapshot.scheduled || snapshot.consumed
                        || SystemClock.uptimeMillis() - snapshot.beganAt > RESTORE_WINDOW_MS) {
                    return;
                }

                // Defer until Chromium's UrlBarViewBinder finishes updating
                // its model and releases ignoreTextChangesForAutocomplete.
                snapshot.scheduled = true;
                log("URLBAR recovery candidate: immediate focus-time clear");
                view.post(() -> restoreIfStillValid(view, snapshot));
            }
        });
    }

    private static void restoreIfStillValid(TextView view, FocusSnapshot snapshot) {
        if (FOCUS_SNAPSHOTS.get(view) != snapshot) return;
        snapshot.consumed = true;
        try {
            long elapsed = SystemClock.uptimeMillis() - snapshot.beganAt;
            if (elapsed > RESTORE_WINDOW_MS
                    || !view.isAttachedToWindow()
                    || !view.hasFocus()
                    || !view.hasWindowFocus()
                    || view.getText() == null
                    || view.getText().length() != 0) {
                log("URLBAR recovery skipped: stale, detached, or user text changed");
                return;
            }

            // This is a best-effort experiment. Setting the editor text
            // normally notifies Chromium's text listeners and lets its
            // AutocompleteInput update. Do not mutate the private model.
            view.setText(snapshot.originalText);
            if (view instanceof EditText) {
                ((EditText) view).selectAll();
            }
            log("URLBAR recovery applied length=" + snapshot.originalText.length());
            view.postDelayed(() -> logUrlBarState("recovery-after-200ms", view), 200L);
        } catch (Throwable t) {
            log("URLBAR recovery failed: " + t.getClass().getSimpleName());
        } finally {
            FOCUS_SNAPSHOTS.remove(view);
        }
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
            // Only Desktop Chrome with an Extensions menu resource. Resource
            // names survive R8 renaming of Java implementation classes.
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
