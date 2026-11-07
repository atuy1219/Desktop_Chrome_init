package com.atuy.desktopchromeinit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Only adds the missing mobile-style clipboard suggestion when Chrome's own
 * CLIPBOARD_SUGGESTION (type 5) is not in its autocomplete adapter.
 *
 * Uses Chromium's actual OmniboxViewHolderFactory, UI resource IDs,
 * Source-verified icon sizes and 3-minute Android clipboard age limit.
 * No clipboard reads happen on startup. No clipboard data are logged.
 */
final class NativeClipboardSuggestion {
    private static final String TAG = "DesktopChromeInit";
    private static final String CHROME = "com.android.chrome";
    private static final int CLIPBOARD_SUGGESTION = 5;
    private static final long DEFAULT_MAX_AGE_MS = 3 * 60 * 1000L;

    static ViewGroup maybeBuild(Activity activity, ViewGroup dropdown,
            TextView urlBar, Boolean offTheRecord) {
        // Never surface a system clipboard item into an OTR window. Do not
        // display it if we cannot positively identify a regular profile.
        if (!Boolean.FALSE.equals(offTheRecord)) {
            Log.i(TAG, "Clipboard card: skipped (incognito/unknown profile)");
            return null;
        }
        if (nativeClipboardPresent(dropdown)) {
            Log.i(TAG, "Clipboard card: native candidate already provided; skipped");
            return null;
        }

        ClipboardManager manager = (ClipboardManager)
                activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) return null;

        final String clipboardUrl;
        try {
            ClipDescription description = manager.getPrimaryClipDescription();
            if (description == null) return null;
            long timestamp = description.getTimestamp();
            long age = System.currentTimeMillis() - timestamp;
            // Chromium ClipboardRecentContent defaults to 3 minutes on
            // Android. A zero/untrusted timestamp is not treated as fresh.
            if (timestamp <= 0 || age < 0 || age > DEFAULT_MAX_AGE_MS) {
                return null;
            }
            boolean textOrUri = description.hasMimeType(
                    ClipDescription.MIMETYPE_TEXT_PLAIN)
                    || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
                    || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST);
            if (!textOrUri) return null;
            // Clipboard is read only after user explicitly focuses Omnibox;
            // this may trigger Android's standard clipboard-access notice.
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() != 1) return null;
            ClipData.Item item = clip.getItemAt(0);
            CharSequence value = item.getText();
            String text = value != null ? value.toString() : null;
            if (TextUtils.isEmpty(text) && item.getUri() != null) {
                text = item.getUri().toString();
            }
            if (TextUtils.isEmpty(text)) return null;
            text = text.trim();
            Uri uri = Uri.parse(text);
            String scheme = uri.getScheme(), host = uri.getHost();
            if (host == null || (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme))) return null;
            clipboardUrl = text;
        } catch (Throwable e) {
            Log.i(TAG, "Clipboard card: Android clipboard unavailable ("
                    + e.getClass().getSimpleName() + ")");
            return null;
        }

        try {
            ViewGroup card = NativeOmniboxPageCard.createChromeSuggestion(
                    dropdown, CLIPBOARD_SUGGESTION);
            if (card == null) return null;
            TextView line1 = getLine(card, activity, "line_1", 0);
            TextView line2 = getLine(card, activity, "line_2", 1);
            if (line1 == null || line2 == null) return null;

            boolean ja = "ja".equals(activity.getResources()
                    .getConfiguration().getLocales().get(0).getLanguage());
            line1.setText(ja ? "コピーしたリンク" : "Copied link");
            line1.setVisibility(View.VISIBLE);
            line2.setText("");
            line2.setVisibility(View.GONE);

            ViewGroup nativeReference = findNativeReference(dropdown, card.getClass());
            if (nativeReference != null) {
                TextView ref1 = getLine(nativeReference, activity, "line_1", 0);
                TextView ref2 = getLine(nativeReference, activity, "line_2", 1);
                if (ref1 != null) line1.setTextColor(ref1.getCurrentTextColor());
                if (ref2 != null) line2.setTextColor(ref2.getCurrentTextColor());
            }

            NativeOmniboxPageCard.configureOriginalSuggestionDimensions(card, activity);
            card.setMinimumHeight(NativeOmniboxPageCard.dimension(
                    activity, "omnibox_suggestion_content_height", 56));
            ImageView decoration = (ImageView) card.getChildAt(0);
            int globe = id(activity, "ic_globe_24dp", "drawable");
            decoration.setImageResource(globe != 0 ? globe
                    : android.R.drawable.ic_menu_view);
            decoration.setImageTintList(
                    ColorStateList.valueOf(line1.getCurrentTextColor()));
            decoration.setVisibility(View.VISIBLE);

            FrameLayout wrapper = new FrameLayout(activity);
            NativeOmniboxPageCard.applyChromeCardSurface(
                    wrapper, activity, nativeReference, false);
            int actionWidth = NativeOmniboxPageCard.dimension(
                    activity, "omnibox_suggestion_action_button_width", 48);
            card.setPaddingRelative(card.getPaddingStart(), card.getPaddingTop(),
                    card.getPaddingEnd() + actionWidth, card.getPaddingBottom());
            wrapper.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL));

            ImageView eye = new ImageView(activity);
            eye.setClickable(true);
            eye.setFocusable(true);
            eye.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int inset = Math.max(0, (actionWidth - dp(activity, 20)) / 2);
            eye.setPadding(inset, inset, inset, inset);
            int eyeIcon = id(activity, "ic_visibility_black", "drawable");
            int concealedIcon = id(activity, "ic_visibility_off_black", "drawable");
            eye.setImageResource(eyeIcon != 0
                    ? eyeIcon : android.R.drawable.ic_menu_view);
            eye.setImageTintList(ColorStateList.valueOf(line1.getCurrentTextColor()));
            eye.setContentDescription(ja ? "リンクを表示" : "Reveal copied link");
            eye.setOnClickListener(v -> {
                boolean reveal = line2.getVisibility() != View.VISIBLE;
                line2.setText(reveal ? clipboardUrl : "");
                line2.setVisibility(reveal ? View.VISIBLE : View.GONE);
                if (concealedIcon != 0 && eyeIcon != 0) {
                    eye.setImageResource(reveal ? concealedIcon : eyeIcon);
                }
                eye.setContentDescription(ja
                        ? (reveal ? "リンクを非表示" : "リンクを表示")
                        : (reveal ? "Hide copied link" : "Reveal copied link"));
            });
            FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                    actionWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.END | Gravity.CENTER_VERTICAL);
            wrapper.addView(eye, actionParams);

            // Tapping the row refines the Omnibox to the copied URL; the user
            // must explicitly submit to navigate. No URL is logged/stored.
            card.setOnClickListener(v -> {
                if (urlBar instanceof EditText) {
                    EditText edit = (EditText) urlBar;
                    edit.setText(clipboardUrl);
                    edit.setSelection(edit.length());
                }
            });
            Log.i(TAG, "Clipboard card: shown using original Chrome view type 5"
                    + " (URL contents redacted)");
            return wrapper;
        } catch (Throwable e) {
            Log.i(TAG, "Clipboard card: native view creation unavailable ("
                    + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static boolean nativeClipboardPresent(ViewGroup dropdown) {
        try {
            Method getAdapter = dropdown.getClass().getMethod("getAdapter");
            Object adapter = getAdapter.invoke(dropdown);
            if (adapter != null) {
                Method size = adapter.getClass().getMethod("getItemCount");
                Method type = adapter.getClass().getMethod("getItemViewType", int.class);
                int count = (Integer) size.invoke(adapter);
                for (int i = 0; i < Math.min(count, 40); i++) {
                    if (((Integer) type.invoke(adapter, i)) == CLIPBOARD_SUGGESTION) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // AndroidX RecyclerView may be obfuscated; inspect visible rows.
        }
        // Defensive duplicate detection for the visible suggestion rows.
        for (int i = 0; i < dropdown.getChildCount(); i++) {
            View child = dropdown.getChildAt(i);
            if (hasClipboardLabel(child, 0)) return true;
        }
        return false;
    }

    private static boolean hasClipboardLabel(View view, int depth) {
        if (depth > 6) return false;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text != null && (
                    "コピーしたリンク".contentEquals(text)
                    || "Copied link".contentEquals(text));
        }
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (hasClipboardLabel(group.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    private static ViewGroup findNativeReference(ViewGroup dropdown, Class<?> cls) {
        for (int i = 0; i < dropdown.getChildCount(); i++) {
            View child = dropdown.getChildAt(i);
            if (child.getClass() == cls && child instanceof ViewGroup) {
                return (ViewGroup) child;
            }
        }
        return null;
    }

    private static TextView getLine(View root, Activity activity,
            String idName, int fallbackIndex) {
        int id = id(activity, idName, "id");
        if (id != 0) {
            View found = root.findViewById(id);
            if (found instanceof TextView) return (TextView) found;
        }
        List<TextView> lines = new ArrayList<>();
        collectTexts(root, lines, 0);
        return lines.size() > fallbackIndex ? lines.get(fallbackIndex) : null;
    }

    private static void collectTexts(View root, List<TextView> out, int depth) {
        if (depth > 6) return;
        if (root instanceof TextView) {
            out.add((TextView) root);
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTexts(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    private static int id(Context ctx, String name, String type) {
        return ctx.getResources().getIdentifier(name, type, CHROME);
    }

    private static int dp(Context ctx, int units) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * units);
    }

    private NativeClipboardSuggestion() {}
}
