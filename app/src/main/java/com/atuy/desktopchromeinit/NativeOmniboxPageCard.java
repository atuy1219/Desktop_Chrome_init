package com.atuy.desktopchromeinit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Outline;
import android.net.Uri;
import android.view.ViewOutlineProvider;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.reflect.Constructor;
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
            String title, String fullUrl, Bitmap favicon, TextView urlBar,
            boolean incognito) {
        try {
            ViewGroup card = createChromeSuggestion(dropdown, EDIT_URL_SUGGESTION);
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
            // Chromium's URL formatter exposes the display URL, not the
            // copy/share target. The full validated URL is retained for all
            // actions; the suggestion displays the readable host.
            Uri parsed = Uri.parse(fullUrl);
            String displayedUrl = parsed.getHost();
            if (displayedUrl == null) displayedUrl = fullUrl;
            second.setText(displayedUrl);
            second.setTextDirection(View.TEXT_DIRECTION_LTR);
            second.setVisibility(View.VISIBLE);

            ViewGroup nativeReference = findReferenceRow(dropdown, card.getClass());
            if (nativeReference != null) {
                applyChromeSuggestionColors(nativeReference, first, second);
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

            configureOriginalSuggestionDimensions(card, activity);

            boolean builtNativeButtons = createNativeActions(
                    card, activity, fullUrl, urlBar, first);
            ViewGroup finalCard = card;
            if (!builtNativeButtons) {
                // R8 can inline/reshape both the action-list builder and its
                // private LayoutParams factory. Keep Chromium's native
                // BaseSuggestionView content and normal suggestion colors.
                // Only the ACTION_BUTTON container is supplied externally.
                finalCard = wrapWithChromeStyleActions(
                        activity, card, nativeReference, fullUrl, urlBar, first,
                        incognito);
                info("native BaseSuggestionView kept; action buttons use "
                        + "measured native-layout overlay");
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
                    dimension(activity, "omnibox_suggestion_content_height", 56));
            card.setMinimumHeight(minimum);
            finalCard.setContentDescription(title + " " + fullUrl);
            if (builtNativeButtons) {
                info("using original Chromium EDIT_URL_SUGGESTION "
                        + "BaseSuggestionView with native ACTION_BUTTON layout");
            } else {
                info("using original Chromium EDIT_URL_SUGGESTION "
                        + "BaseSuggestionView with external action overlay");
            }
            return finalCard;
        } catch (Throwable error) {
            Log.w(TAG, "Current-page card native rendering failed", error);
            return null;
        }
    }

    private static void applyChromeSuggestionColors(
            ViewGroup reference, TextView title, TextView url) {
        // The Chrome-native layout explicitly declares R.id.line_1 and
        // R.id.line_2. Prefer those stable resource IDs over indiscriminately
        // walking ActionChipsView descendants, which can contain unrelated
        // text nodes and use unrelated colors.
        int firstId = id(reference.getContext(), "line_1");
        int secondId = id(reference.getContext(), "line_2");
        View first = firstId == 0 ? null : reference.findViewById(firstId);
        View second = secondId == 0 ? null : reference.findViewById(secondId);
        TextView primary = first instanceof TextView ? (TextView) first : null;
        TextView secondary = second instanceof TextView ? (TextView) second : null;
        if (primary == null) {
            List<TextView> lines = new ArrayList<>();
            collectTextViews(reference, lines, 0);
            if (lines.isEmpty()) return;
            primary = lines.get(0);
            if (lines.size() > 1) secondary = lines.get(1);
        }
        title.setTextColor(primary.getCurrentTextColor());
        url.setTextColor(secondary != null
                ? secondary.getCurrentTextColor()
                : primary.getCurrentTextColor());
    }

    static void configureOriginalSuggestionDimensions(
            ViewGroup view, Context context) {
        // Newly inflated BaseSuggestionView is unbound, so its four
        // SuggestionLayout dimensions start as zeros. This was why the
        // favicon and text overlapped the left edge in v0.4.23.
        final int[] sizes = {
                dimension(context, "omnibox_suggestion_icon_area_size", 56),
                dimension(context, "omnibox_suggestion_content_height", 56),
                dimension(context, "omnibox_suggestion_compact_content_height", 48),
                dimension(context, "omnibox_suggestion_content_padding", 8)
        };
        for (Class<?> cls = view.getClass(); cls != null
                && cls != ViewGroup.class && cls != View.class;
                cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() != void.class || p.length != 4
                        || p[0] != int.class || p[1] != int.class
                        || p[2] != int.class || p[3] != int.class) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(view, sizes[0], sizes[1], sizes[2], sizes[3]);
                    info("initialized native SuggestionLayout dimensions "
                            + sizes[0] + "/" + sizes[1] + "/" + sizes[2]);
                    return;
                } catch (Throwable ignored) {}
            }
        }
        info("native SuggestionLayout dimension binder R8-inaccessible");
    }

    static int dimension(Context ctx, String name, int fallbackDp) {
        int id = id(ctx, name, "dimen");
        return id == 0
                ? dp(ctx, fallbackDp)
                : ctx.getResources().getDimensionPixelSize(id);
    }

    static void applyChromeCardSurface(
            FrameLayout wrapper, Activity activity, ViewGroup reference,
            boolean incognito) {
        // Chromium OmniboxResourceProvider.getStandardSuggestionBackgroundColor()
        // uses exactly these two resources. Sample the installed APK to
        // respect dark/light theme, Incognito, and Google's future changes.
        String name = incognito
                ? "search_suggestion_bg_color_incognito"
                : "search_suggestion_bg_color";
        int colorResource = id(activity, name, "color");
        int color = colorResource != 0 ? activity.getColor(colorResource)
                : 0xff10151b;
        final int radius = dimension(
                activity, "omnibox_suggestion_bg_round_corner_radius", 16);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        wrapper.setBackground(shape);
        wrapper.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        wrapper.setClipToOutline(true);
    }

    private static ViewGroup wrapWithChromeStyleActions(
            Activity activity, ViewGroup chromeRow, ViewGroup reference,
            String fullUrl, TextView urlBar, TextView title,
            boolean incognito) {
        FrameLayout wrapper = new FrameLayout(activity);
        wrapper.setMinimumHeight(dimension(
                activity, "omnibox_suggestion_content_height", 56));
        applyChromeCardSurface(wrapper, activity, reference, incognito);
        int actionWidthId = id(activity, "omnibox_suggestion_action_button_width", "dimen");
        int actionWidth = actionWidthId == 0
                ? dp(activity, 48)
                : activity.getResources().getDimensionPixelSize(actionWidthId);
        if (actionWidth < dp(activity, 32) || actionWidth > dp(activity, 80)) {
            actionWidth = dp(activity, 48);
        }
        final int reservedWidth = 3 * actionWidth;
        chromeRow.setPaddingRelative(
                chromeRow.getPaddingStart(), chromeRow.getPaddingTop(),
                chromeRow.getPaddingEnd() + reservedWidth,
                chromeRow.getPaddingBottom());
        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL);
        wrapper.addView(chromeRow, rowParams);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams buttonsParams = new FrameLayout.LayoutParams(
                reservedWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL);

        final String[] names = {
                "ic_share_white_24dp", "ic_content_copy", "ic_edit_24dp"
        };
        final int[] fallbackIcons = {
                android.R.drawable.ic_menu_share,
                android.R.drawable.ic_menu_save,
                android.R.drawable.ic_menu_edit
        };
        final String[] labelsJa = {"共有", "コピー", "編集"};
        final String[] labelsEn = {"Share", "Copy", "Edit"};
        Runnable[] handlers = {
                () -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, fullUrl);
                    activity.startActivity(Intent.createChooser(send, null));
                },
                () -> {
                    ClipboardManager cb = (ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("URL", fullUrl));
                    }
                },
                () -> {
                    if (urlBar instanceof EditText) {
                        EditText editor = (EditText) urlBar;
                        editor.setText(fullUrl);
                        editor.setSelection(editor.length());
                    }
                }
        };
        boolean ja = "ja".equals(activity.getResources()
                .getConfiguration().getLocales().get(0).getLanguage());
        int foreground = id(activity, "action_button_foreground_selector", "drawable");

        for (int i = 0; i < 3; i++) {
            ImageView button = instantiateChromeActionButton(reference, activity);
            if (button == null) button = new ImageView(activity);
            button.setClickable(true);
            button.setFocusable(true);
            // Android ImageView.CENTER draws the full 24dp vector, which is
            // visibly larger than Chrome's 20dp Omnibox small-icon policy.
            // Keep a 48dp touch target but constrain the drawable area.
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int inset = Math.max(0, (actionWidth - dp(activity, 20)) / 2);
            button.setPadding(inset, inset, inset, inset);
            if (foreground != 0) {
                button.setForeground(activity.getDrawable(foreground));
            }
            int icon = id(activity, names[i], "drawable");
            button.setImageResource(icon == 0 ? fallbackIcons[i] : icon);
            button.setImageTintList(
                    ColorStateList.valueOf(title.getCurrentTextColor()));
            button.setContentDescription(ja ? labelsJa[i] : labelsEn[i]);
            final Runnable callback = handlers[i];
            button.setOnClickListener(view -> callback.run());
            buttons.addView(button, new LinearLayout.LayoutParams(
                    actionWidth, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        wrapper.addView(buttons, buttonsParams);
        info("action overlay widthPerButtonPx=" + actionWidth
                + " sourceChromeSuggestion=" + (reference != null));
        return wrapper;
    }

    private static ImageView instantiateChromeActionButton(
            ViewGroup nativeReference, Activity activity) {
        if (nativeReference == null) return null;
        // Source: the first three children are favicon, chip footer, content.
        // Remaining ImageViews on an already-bound native suggestion are
        // Chromium ActionButtonView instances, which have Context ctor.
        for (int i = 3; i < nativeReference.getChildCount(); i++) {
            View item = nativeReference.getChildAt(i);
            if (!(item instanceof ImageView)) continue;
            try {
                Constructor<?> ctor = item.getClass().getDeclaredConstructor(Context.class);
                ctor.setAccessible(true);
                Object instance = ctor.newInstance(activity);
                if (instance instanceof ImageView) return (ImageView) instance;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int dp(Context context, int units) {
        return Math.round(units * context.getResources().getDisplayMetrics().density);
    }

    static ViewGroup createChromeSuggestion(ViewGroup dropdown, int type)
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
                        Object created = method.invoke(factory, dropdown, type);
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
        if (!added) {
            // R8 may inline BaseSuggestionView.setActionButtonsCount(int).
            // SuggestionLayout's layout params (from its existing decoration
            // child) still encode the stable ACTION_BUTTON role as type=2.
            // Add Android ImageViews with *Chrome's own* action ViewGroup
            // layout params, rather than falling back to an unrelated
            // 2-row custom card.
            if (!appendNativeLayoutActionButtons(card, activity)) {
                discardPartialActionChildren(card, initial);
                return false;
            }
            info("R8 removed native action builder; using native action layout");
        }

        List<ImageView> actions = new ArrayList<>();
        for (int i = initial; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child instanceof ImageView) actions.add((ImageView) child);
        }
        if (actions.size() != 3) {
            discardPartialActionChildren(card, initial);
            return false;
        }

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

    private static void discardPartialActionChildren(
            ViewGroup card, int originalChildCount) {
        while (card.getChildCount() > originalChildCount) {
            card.removeViewAt(card.getChildCount() - 1);
        }
    }

    private static boolean appendNativeLayoutActionButtons(
            ViewGroup card, Activity activity) {
        if (card.getChildCount() < 1) return false;
        ViewGroup.LayoutParams reference = card.getChildAt(0).getLayoutParams();
        if (reference == null) return false;
        Class<?> layoutClass = reference.getClass();
        ViewGroup.LayoutParams[] params = new ViewGroup.LayoutParams[3];

        try {
            for (int i = 0; i < params.length; i++) {
                params[i] = nativeActionLayoutParams(layoutClass);
                if (params[i] == null) return false;
            }
            int foreground = id(activity, "action_button_foreground_selector",
                    "drawable");
            for (int i = 0; i < params.length; i++) {
                ImageView action = new ImageView(activity);
                action.setClickable(true);
                action.setFocusable(true);
                action.setScaleType(ImageView.ScaleType.CENTER);
                if (foreground != 0) {
                    action.setForeground(activity.getDrawable(foreground));
                }
                card.addView(action, params[i]);
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Native ACTION_BUTTON layout fallback failed", error);
            return false;
        }
    }

    private static ViewGroup.LayoutParams nativeActionLayoutParams(
            Class<?> layoutClass) {
        for (Method method : layoutClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != int.class
                    || !ViewGroup.LayoutParams.class.isAssignableFrom(
                            method.getReturnType())) continue;
            try {
                method.setAccessible(true);
                Object result = method.invoke(null, 2);
                if (result instanceof ViewGroup.LayoutParams) {
                    return (ViewGroup.LayoutParams) result;
                }
            } catch (Throwable ignored) {}
        }

        // Source: SuggestionLayout.LayoutParams(int width, int height,
        // @SuggestionViewType int type, boolean largeDecoration).
        for (Constructor<?> ctor : layoutClass.getDeclaredConstructors()) {
            Class<?>[] args = ctor.getParameterTypes();
            if (args.length != 4 || args[0] != int.class || args[1] != int.class
                    || args[2] != int.class || args[3] != boolean.class) continue;
            try {
                ctor.setAccessible(true);
                Object result = ctor.newInstance(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 2, false);
                if (result instanceof ViewGroup.LayoutParams) {
                    return (ViewGroup.LayoutParams) result;
                }
            } catch (Throwable ignored) {}
        }
        return null;
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
            List<ImageView> fallbackIcons = new ArrayList<>();
            collectImages(card, fallbackIcons, 0);
            for (int j = 0; j < fallbackIcons.size(); j++) {
                fallbackIcons.get(j).setImageTintList(ColorStateList.valueOf(
                        j == 0 ? secondary : primary));
            }
            Drawable source = reference.getBackground();
            if (source != null && source.getConstantState() != null) {
                card.setBackground(source.getConstantState().newDrawable().mutate());
            }
            break;
        }
    }

    private static void collectImages(
            View view, List<ImageView> out, int depth) {
        if (depth > 7) return;
        if (view instanceof ImageView) {
            out.add((ImageView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectImages(group.getChildAt(i), out, depth + 1);
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
