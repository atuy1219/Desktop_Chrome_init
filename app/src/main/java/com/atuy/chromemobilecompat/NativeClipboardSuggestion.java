package com.atuy.chromemobilecompat;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.atuy.chromemobilecompat.compat.XposedBridge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Missing mobile clipboard candidate only; no logging or persistent clipboard storage. */
final class NativeClipboardSuggestion {
    private static final int CLIPBOARD_SUGGESTION = 5;
    private static final long MAX_AGE_MS = 180_000L;
    private static final int MAX_TEXT_LENGTH = 32_768;

    private static final class Item {
        final long timestamp;
        final String text;
        final boolean url;
        Item(long timestamp, String text, boolean url) {
            this.timestamp = timestamp; this.text = text; this.url = url;
        }
    }

    private static Item read(ClipboardManager manager) {
        try {
            ClipDescription description = manager.getPrimaryClipDescription();
            if (description == null) return null;
            long stamp = description.getTimestamp();
            long age = System.currentTimeMillis() - stamp;
            if (stamp <= 0 || age < 0 || age > MAX_AGE_MS) return null;
            PersistableBundle extras = description.getExtras();
            if (extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false))
                return null;
            boolean plain = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML);
            boolean uriList = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST);
            if (!plain && !uriList) return null;
            ClipData data = manager.getPrimaryClip();
            if (data == null || data.getItemCount() != 1
                    || data.getDescription().getTimestamp() != stamp) return null;
            ClipData.Item item = data.getItemAt(0);
            CharSequence raw = item.getText();
            boolean uriOnly = raw == null;
            String text = raw != null ? raw.toString()
                    : item.getUri() != null ? item.getUri().toString() : null;
            if (text == null || text.length() > MAX_TEXT_LENGTH || text.trim().isEmpty()) return null;
            String trimmed = text.trim();
            Uri uri = Uri.parse(trimmed);
            boolean url = !trimmed.matches("(?s).*\\s.*")
                    && uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
            if (uriOnly && !url) return null;
            return new Item(stamp, url ? trimmed : text, url);
        } catch (Throwable e) {
            XposedBridge.log("Clipboard card: read unavailable (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    static ViewGroup maybeBuild(Activity activity, ViewGroup dropdown,
            TextView urlBar, Boolean offTheRecord) {
        if (!Boolean.FALSE.equals(offTheRecord) || !urlBar.hasFocus()
                || !activity.hasWindowFocus()) {
            XposedBridge.log("Clipboard card: skipped (profile/focus not verified)");
            return null;
        }
        if (nativeClipboardPresent(dropdown)) return null;
        ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return null;
        Item initial = read(manager);
        if (initial == null) return null;
        // Retain only timestamp/type while hidden. Re-read the same clip upon
        // an explicit reveal/refine action, rejecting replacements/expiry.
        final long stamp = initial.timestamp;
        final boolean url = initial.url;
        final boolean ja = "ja".equals(activity.getResources().getConfiguration()
                .getLocales().get(0).getLanguage());
        int height = Math.max(dp(activity, 56), NativeOmniboxPageCard.dimension(
                activity, "omnibox_suggestion_content_height", 56));
        int lead = Math.max(OmniboxIcons.pageIconSize(activity), NativeOmniboxPageCard.dimension(
                activity, "omnibox_suggestion_icon_area_size", 56));
        int actionWidth = NativeOmniboxPageCard.dimension(activity,
                "omnibox_suggestion_action_button_width", 48);
        if (actionWidth < dp(activity, 32) || actionWidth > dp(activity, 80))
            actionWidth = dp(activity, 48);

        // Reuse the native row's text metrics and colors, but use a fixed-height
        // role layout here: an unbound clipboard ViewHolder can have zero lead
        // width or be removed by R8, just like the current-page decoration.
        List<TextView> reference = new ArrayList<>();
        ViewGroup nativeReference = null;
        for (int i = 0; i < dropdown.getChildCount(); i++) {
            View child = dropdown.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            collectTexts(child, reference, 0);
            if (!reference.isEmpty()) { nativeReference = (ViewGroup) child; break; }
        }
        int primary = reference.isEmpty() ? urlBar.getCurrentTextColor()
                : reference.get(0).getCurrentTextColor();
        int secondary = reference.size() > 1 ? reference.get(1).getCurrentTextColor() : primary;

        FrameLayout row = new FrameLayout(activity);
        row.setLayoutDirection(dropdown.getLayoutDirection());
        NativeOmniboxPageCard.applyChromeCardSurface(row, activity, nativeReference, false);
        row.setMinimumHeight(height);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));

        FrameLayout leading = new FrameLayout(activity);
        content.addView(leading, new LinearLayout.LayoutParams(lead, height));
        int edge = OmniboxIcons.pageIconSize(activity);
        ImageView decoration = new ImageView(activity);
        leading.addView(decoration, new FrameLayout.LayoutParams(edge, edge, Gravity.CENTER));
        OmniboxIcons.suggestionGlyph(decoration, url ? "ic_globe_24dp" : "ic_search_24dp", primary);

        LinearLayout lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(lines, new LinearLayout.LayoutParams(0, height, 1f));
        TextView title = textLine(activity, reference.isEmpty() ? null : reference.get(0), primary, 16);
        title.setText(ja ? (url ? "コピーしたリンク" : "コピーしたテキスト")
                : (url ? "Copied link" : "Copied text"));
        lines.addView(title);
        TextView detail = textLine(activity, reference.size() > 1 ? reference.get(1) : null, secondary, 14);
        detail.setVisibility(View.GONE);
        lines.addView(detail);

        ImageView eye = new ImageView(activity);
        eye.setClickable(true);
        eye.setFocusable(true);
        eye.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int insetX = Math.max(0, (actionWidth - edge) / 2);
        int insetY = Math.max(0, (height - edge) / 2);
        eye.setPadding(insetX, insetY, insetX, insetY);
        content.addView(eye, new LinearLayout.LayoutParams(actionWidth, height));
        int foreground = activity.getResources().getIdentifier(
                "action_button_foreground_selector", "drawable", "com.android.chrome");
        if (foreground != 0) eye.setForeground(activity.getDrawable(foreground));
        OmniboxIcons.suggestionGlyph(eye, "ic_visibility_black", primary);
        eye.setContentDescription(ja ? "クリップボードの内容を表示" : "Reveal clipboard contents");
        eye.setOnClickListener(v -> {
            if (detail.getVisibility() == View.VISIBLE) {
                detail.setText("");
                detail.setVisibility(View.GONE);
                OmniboxIcons.suggestionGlyph(eye, "ic_visibility_black", primary);
                eye.setContentDescription(ja ? "クリップボードの内容を表示" : "Reveal clipboard contents");
                return;
            }
            Item current = read(manager);
            if (current == null || current.timestamp != stamp || current.url != url
                    || !urlBar.hasFocus() || !activity.hasWindowFocus()) return;
            detail.setText(current.text);
            detail.setVisibility(View.VISIBLE);
            OmniboxIcons.suggestionGlyph(eye, "ic_visibility_off_black", primary);
            eye.setContentDescription(ja ? "クリップボードの内容を非表示" : "Conceal clipboard contents");
        });
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (!(urlBar instanceof EditText) || !urlBar.hasFocus() || !activity.hasWindowFocus()) return;
            Item current = read(manager);
            if (current == null || current.timestamp != stamp || current.url != url) return;
            EditText edit = (EditText) urlBar;
            edit.setText(current.text);
            edit.setSelection(edit.length());
        });
        // Expire revealed text and hide the entire stale row without retaining
        // clipboard content in module fields or preferences.
        row.postDelayed(() -> {
            detail.setText("");
            detail.setVisibility(View.GONE);
            row.setVisibility(View.INVISIBLE);
        }, Math.max(1L, MAX_AGE_MS - (System.currentTimeMillis() - stamp)));
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { detail.setText(""); }
        });
        XposedBridge.log("Clipboard card: shown kind=" + (url ? "URL" : "TEXT") + " (content redacted)");
        return row;
    }

    private static TextView textLine(Activity activity, TextView reference, int color, int sp) {
        TextView text = new TextView(activity);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setTextColor(color);
        if (reference != null) {
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, reference.getTextSize());
            text.setTypeface(reference.getTypeface());
            text.setIncludeFontPadding(reference.getIncludeFontPadding());
        } else text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return text;
    }

    static boolean nativeClipboardPresent(ViewGroup dropdown) {
        try {
            Object adapter = dropdown.getClass().getMethod("getAdapter").invoke(dropdown);
            if (adapter != null) {
                Method size = adapter.getClass().getMethod("getItemCount");
                Method type = adapter.getClass().getMethod("getItemViewType", int.class);
                for (int i = 0, count = (Integer) size.invoke(adapter); i < Math.min(count, 40); i++)
                    if (((Integer) type.invoke(adapter, i)) == CLIPBOARD_SUGGESTION) return true;
            }
        } catch (Throwable ignored) {}
        for (int i = 0; i < dropdown.getChildCount(); i++) {
            List<TextView> texts = new ArrayList<>();
            collectTexts(dropdown.getChildAt(i), texts, 0);
            for (TextView text : texts) {
                CharSequence value = text.getText();
                if (value != null && ("コピーしたリンク".contentEquals(value)
                        || "コピーしたテキスト".contentEquals(value)
                        || "Copied link".contentEquals(value) || "Copied text".contentEquals(value)))
                    return true;
            }
        }
        return false;
    }

    private static void collectTexts(View root, List<TextView> out, int depth) {
        if (depth > 6) return;
        if (root instanceof TextView) out.add((TextView) root);
        else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), out, depth + 1);
        }
    }

    private static int dp(Context ctx, int units) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * units);
    }
    private NativeClipboardSuggestion() {}
}
