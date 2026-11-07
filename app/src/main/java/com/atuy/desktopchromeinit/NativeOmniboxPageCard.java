package com.atuy.desktopchromeinit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reuses Chromium 158's ACTUAL EDIT_URL_SUGGESTION ViewHolder factory.
 *
 * EDIT_URL_SUGGESTION (type 1) uses the SAME BaseSuggestionView with the
 * omnibox_basic_suggestion two-line content as ordinary suggestions.
 * Android-only reflection locates that existing factory from the visible
 * OmniboxSuggestionsDropdown without naming R8-obfuscated classes.
 * The C++ build suppresses its backing autocomplete match; this supplies
 * the original view/geometry and standard Android click actions instead.
 */
final class NativeOmniboxPageCard {
    private static final String TAG = "DesktopChromeInit";
    private static final String CHROME = "com.android.chrome";
    private static final int EDIT_URL_SUGGESTION = 1; // Chromium 158 enum

    static ViewGroup build(Activity activity, ViewGroup dropdown,
            String title, String fullUrl, Bitmap favicon, TextView urlBar) {
        try {
            ViewGroup card = createFromChromeFactory(dropdown);
            if (card == null) {
                info("native Omnibox factory not accessible");
                return null;
            }

            // The Chromium factory creates BaseSuggestionView directly from
            // R.layout.omnibox_basic_suggestion. Android XML inflation has
            // already supplied Chrome's own typography and theme resources.
            List<TextView> textViews = new ArrayList<>();
            collectTextViews(card, textViews, 0);
            if (textViews.size() < 2) {
                info("native view lacks two text lines");
                return null;
            }

            TextView first = textViews.get(0);
            TextView second = textViews.get(1);
            int idFirst = id(activity, "line_1");
            int idSecond = id(activity, "line_2");
            if (idFirst != 0 && card.findViewById(idFirst) instanceof TextView) {
                first = card.findViewById(idFirst);
            }
            if (idSecond != 0 && card.findViewById(idSecond) instanceof TextView) {
                second = card.findViewById(idSecond);
            }
            first.setText(title);
            first.setVisibility(View.VISIBLE);
            second.setText(fullUrl);
            second.setTextDirection(View.TEXT_DIRECTION_LTR);
            second.setVisibility(View.VISIBLE);

            ViewGroup nativeReference = findReferenceRow(dropdown, card.getClass());
            if (nativeReference != null) {
                // SuggestionLayout's 4 sizing fields are initialized through
                // BaseSuggestionViewBinder in ordinary suggestions. Copy
                // layout primitives from an already-bound native row of the
                // SAME CLASS; do not hard-code Android density or font scale.
                copySuggestionLayoutGeometry(nativeReference, card);
                if (nativeReference.getMinimumHeight() > 0) {
                    card.setMinimumHeight(nativeReference.getMinimumHeight());
                }
                Drawable refBackground = nativeReference.getBackground();
                if (refBackground != null && refBackground.getConstantState() != null) {
                    card.setBackground(refBackground.getConstantState()
                            .newDrawable().mutate());
                }
            }

            if (!createNativeActions(card, activity, fullUrl, urlBar, first)) {
                info("native action buttons could not be configured");
                return null;
            }

            // The native EditUrlSuggestionProcessor sets its decoration to
            // the page favicon via fetchSuggestionFavicon(). Prefer the
            // current tab favicon; use Chromium's globe drawable otherwise.
            if (card.getChildCount() > 0 && card.getChildAt(0) instanceof ImageView) {
                ImageView decoration = (ImageView) card.getChildAt(0);
                if (favicon != null) {
                    decoration.setImageBitmap(favicon);
                } else {
                    int globe = id(activity, "ic_globe_24dp", "drawable");
                    decoration.setImageResource(
                            globe != 0 ? globe : android.R.drawable.ic_menu_view);
                }
                decoration.setVisibility(View.VISIBLE);
            }

            // Chrome computes minHeight from resource provider while binding.
            // A native basic suggestion view with two lines is at least the
            // measured height of the same-class reference row.
            int minimum = Math.max(card.getMinimumHeight(),
                    Math.round(56f * activity.getResources().getDisplayMetrics().density));
            card.setMinimumHeight(minimum);
            card.setContentDescription(title + " " + fullUrl);
            info("using original Chromium EDIT_URL_SUGGESTION BaseSuggestionView");
            return card;
        } catch (Throwable error) {
            Log.w(TAG, "Current-page card native rendering failed", error);
            return null;
        }
    }

    private static ViewGroup createFromChromeFactory(ViewGroup dropdown)
            throws IllegalAccessException {
        Class<?> cls = dropdown.getClass();
        for (; cls != null && cls != View.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object factory;
                try {
                    field.setAccessible(true);
                    factory = field.get(dropdown);
                } catch (Throwable ignored) {
                    continue;
                }
                if (factory == null) continue;
                for (Method method : factory.getClass().getDeclaredMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (p.length != 2 || !ViewGroup.class.isAssignableFrom(p[0])
                            || p[1] != int.class) continue;
                    try {
                        method.setAccessible(true);
                        Object created = method.invoke(factory, dropdown, EDIT_URL_SUGGESTION);
                        if (created instanceof ViewGroup) {
                            ViewGroup row = (ViewGroup) created;
                            if (looksLikeNativeSuggestion(row)) return row;
                        }
                        View v = itemViewFromHolder(created);
                        if (v instanceof ViewGroup && looksLikeNativeSuggestion((ViewGroup) v)) {
                            return (ViewGroup) v;
                        }
                    } catch (Throwable ignored) {
                        // Some ViewHolder methods may require initialization.
                    }
                }
            }
        }
        return null;
    }

    private static View itemViewFromHolder(Object holder) {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!View.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(holder);
                    if (value instanceof View) return (View) value;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean looksLikeNativeSuggestion(ViewGroup view) {
        if (view.getChildCount() < 2) return false;
        List<TextView> lines = new ArrayList<>();
        collectTextViews(view, lines, 0);
        // The source-verified view has a two-line content block.
        return lines.size() >= 2 && view.getChildAt(0) instanceof ImageView;
    }

    private static void collectTextViews(View view, List<TextView> out, int depth) {
        if (depth > 7) return;
        if (view instanceof TextView) {
            out.add((TextView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectTextViews(group.getChildAt(i), out, depth + 1);
        }
    }

    private static ViewGroup findReferenceRow(ViewGroup dropdown, Class<?> rowClass) {
        for (int i = 0; i < dropdown.getChildCount(); i++) {
            View v = dropdown.getChildAt(i);
            if (v instanceof ViewGroup && v.getClass() == rowClass) {
                return (ViewGroup) v;
            }
        }
        return null;
    }

    private static void copySuggestionLayoutGeometry(
            ViewGroup reference, ViewGroup target) {
        // In Chromium 158, BaseSuggestionView extends SuggestionLayout.
        // Copy only its instance int fields (dimensions and paddings),
        // excluding Android framework and any mutable View collections.
        for (Class<?> cls = reference.getClass().getSuperclass();
                cls != null && cls != ViewGroup.class && cls != View.class;
                cls = cls.getSuperclass()) {
            if (!cls.getName().equals(target.getClass().getSuperclass().getName())) {
                continue;
            }
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || Modifier.isFinal(field.getModifiers())
                        || field.getType() != int.class) continue;
                try {
                    field.setAccessible(true);
                    field.setInt(target, field.getInt(reference));
                } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean createNativeActions(ViewGroup card, Activity activity,
            String fullUrl, TextView urlBar, TextView nativeLine1) {
        int initial = card.getChildCount();
        boolean added = false;
        for (Method method : card.getClass().getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != int.class
                    || method.getReturnType() != void.class) continue;
            try {
                method.setAccessible(true);
                method.invoke(card, 3);
                if (card.getChildCount() == initial + 3) {
                    added = true;
                    break;
                }
            } catch (Throwable ignored) {}
        }
        if (!added) return false;

        List<ImageView> actions = new ArrayList<>();
        for (int i = initial; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof ImageView) actions.add((ImageView) child);
        }
        if (actions.size() != 3) return false;

        // Chrome's native BaseSuggestionView creates these three
        // ActionButtonView instances with the right hit area, focus,
        // foreground, hover and accessibility behavior.
        Runnable[] callbacks = new Runnable[] {
            () -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, fullUrl);
                activity.startActivity(Intent.createChooser(send, null));
            },
            () -> {
                ClipboardManager cb = (ClipboardManager) activity.getSystemService(
                        Context.CLIPBOARD_SERVICE);
                if (cb != null) {
                    cb.setPrimaryClip(ClipData.newPlainText("URL", fullUrl));
                }
            },
            () -> {
                if (urlBar instanceof EditText) {
                    EditText edit = (EditText) urlBar;
                    edit.setText(fullUrl);
                    edit.setSelection(edit.length());
                }
            }
        };
        String[] iconNames = {
            "ic_share_white_24dp", "ic_content_copy", "ic_edit_24dp"
        };
        String[] jaLabels = {"共有", "コピー", "編集"};
        String[] enLabels = {"Share", "Copy", "Edit"};
        int[] fallback = {
            android.R.drawable.ic_menu_share,
            android.R.drawable.ic_menu_save,
            android.R.drawable.ic_menu_edit
        };
        boolean japanese = "ja".equals(activity.getResources().getConfiguration()
                .getLocales().get(0).getLanguage());
        ColorStateList tint = ColorStateList.valueOf(nativeLine1.getCurrentTextColor());
        for (int i = 0; i < actions.size(); i++) {
            ImageView action = actions.get(i);
            int resource = id(activity, iconNames[i], "drawable");
            action.setImageResource(resource == 0 ? fallback[i] : resource);
            action.setImageTintList(tint);
            action.setForegroundTintList(tint);
            action.setVisibility(View.VISIBLE);
            action.setContentDescription(japanese ? jaLabels[i] : enLabels[i]);
            final Runnable callback = callbacks[i];
            // Override the ActionButtonView's default tag-based callback,
            // which would be null unless a native PropertyModel is bound.
            action.setOnClickListener(v -> callback.run());
        }
        return true;
    }

    static void styleLegacyFallback(ViewGroup dropdown, ViewGroup card) {
        // If Google has optimized out the source factory, at least derive
        // contrast from a genuine Omnibox item instead of Android's unrelated
        // Activity theme. This fixes unreadable dark-on-dark fallback text.
        for (int i = 0; i < dropdown.getChildCount(); i++) {
            View reference = dropdown.getChildAt(i);
            List<TextView> nativeTexts = new ArrayList<>();
            collectTextViews(reference, nativeTexts, 0);
            if (nativeTexts.isEmpty()) continue;
            List<TextView> ours = new ArrayList<>();
            collectTextViews(card, ours, 0);
            int primary = nativeTexts.get(0).getCurrentTextColor();
            int secondary = nativeTexts.size() > 1
                    ? nativeTexts.get(1).getCurrentTextColor() : primary;
            for (int j = 0; j < ours.size(); j++) {
                ours.get(j).setTextColor(j == 0 ? primary : secondary);
            }
            Drawable source = reference.getBackground();
            if (source != null && source.getConstantState() != null) {
                card.setBackground(source.getConstantState().newDrawable().mutate());
            }
            break;
        }
    }

    private static int id(Context ctx, String name) {
        return id(ctx, name, "id");
    }

    private static int id(Context ctx, String name, String type) {
        return ctx.getResources().getIdentifier(name, type, CHROME);
    }

    private static void info(String message) {
        Log.i(TAG, "Current-page card native: " + message);
    }

    private NativeOmniboxPageCard() {}
}
