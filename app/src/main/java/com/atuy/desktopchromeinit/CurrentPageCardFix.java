package com.atuy.desktopchromeinit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import com.atuy.desktopchromeinit.compat.XC_MethodHook;
import com.atuy.desktopchromeinit.compat.XposedBridge;

/**
 * Android-only companion to the native Chromium edit-URL suggestion.
 *
 * The Desktop Android C++ build disables ZeroSuggestVerbatimMatchProvider on
 * ordinary web pages, so the existing Java EditUrlSuggestionProcessor has no
 * what-you-typed match to render. This view adds only the missing current-page
 * row without changing Chromium's suggestion list, URL edit text, or IME
 * behavior. Uses Android view IDs and validated live Tab data, not R8 names.
 *
 * A failed Tab/URL lookup is a no-op: never use a shortened display URL for
 * Share, Copy or Edit.
 */
final class CurrentPageCardFix {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET_PACKAGE = "com.android.chrome";
    private static final Map<TextView, CardSession> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean installed;

    private static final class Page {
        final String url;
        final String title;
        final Bitmap favicon;

        Page(String url, String title, Bitmap favicon) {
            this.url = url;
            this.title = title;
            this.favicon = favicon;
        }
    }

    private static final class CardSession {
        final TextView urlBar;
        final String displayBeforeFocus;
        ViewGroup dropdown;
        FrameLayout suggestionContainer;
        LinearLayout card;
        int baselineTopPadding;
        int cardHeight;
        android.view.ViewTreeObserver.OnPreDrawListener layoutObserver;

        CardSession(TextView urlBar, String displayBeforeFocus) {
            this.urlBar = urlBar;
            this.displayBeforeFocus = displayBeforeFocus;
        }
    }

    static synchronized void install() {
        if (installed) return;
        try {
            Method focus = TextView.class.getDeclaredMethod(
                    "onFocusChanged", boolean.class, int.class, Rect.class);
            focus.setAccessible(true);
            XposedBridge.hookMethod(focus, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof TextView)
                            || !(param.args[0] instanceof Boolean)) return;
                    TextView view = (TextView) param.thisObject;
                    if (!isChromeUrlBar(view)) return;
                    if ((Boolean) param.args[0]) {
                        onFocus(view);
                    } else {
                        CardSession session = SESSIONS.remove(view);
                        if (session != null) removeCard(session);
                    }
                }
            });
            installed = true;
            Log.i(TAG, "Current-page card: Android focus observer installed");
        } catch (Throwable e) {
            Log.w(TAG, "Current-page card observer unavailable", e);
        }
    }

    private static boolean isChromeUrlBar(TextView view) {
        try {
            return TARGET_PACKAGE.equals(view.getContext().getPackageName())
                    && view.getId() != View.NO_ID
                    && "url_bar".equals(
                            view.getResources().getResourceEntryName(view.getId()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void onFocus(TextView urlBar) {
        CardSession old = SESSIONS.get(urlBar);
        if (old != null) removeCard(old);

        CardSession session = new CardSession(
                urlBar, String.valueOf(urlBar.getText()));
        SESSIONS.put(urlBar, session);

        // A TextWatcher is Android's public API; do not intercept Chromium's
        // obfuscated autocomplete methods or replace any existing listener.
        if (!WATCHERS.containsKey(urlBar)) {
            try {
                urlBar.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(
                            CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(
                            CharSequence s, int start, int before, int count) {
                        CardSession active = SESSIONS.get(urlBar);
                        if (active == null) return;
                        if (!TextUtils.isEmpty(s)) {
                            removeCard(active);
                        } else if (urlBar.hasFocus()) {
                            urlBar.postDelayed(() -> tryShow(active, 0), 30);
                        }
                    }
                    @Override public void afterTextChanged(Editable s) {}
                });
                // Avoid setTag(key), which demands an app-resource ID key.
                WATCHERS.put(urlBar, Boolean.TRUE);
            } catch (Throwable e) {
                Log.w(TAG, "Current-page card TextWatcher unavailable", e);
            }
        }
        urlBar.postDelayed(() -> tryShow(session, 0), 100);
    }

    private static final Map<TextView, Boolean> WATCHERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static void tryShow(CardSession session, int attempt) {
        TextView bar = session.urlBar;
        if (!bar.hasFocus() || SESSIONS.get(bar) != session
                || !TextUtils.isEmpty(bar.getText())) return;
        if (session.card != null) return;

        Activity activity = activityFor(bar.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        int dropdownId = bar.getResources().getIdentifier(
                "omnibox_suggestions_dropdown", "id", TARGET_PACKAGE);
        View dropdown = dropdownId == 0 ? null : activity.findViewById(dropdownId);
        if (!(dropdown instanceof ViewGroup)
                || !(dropdown.getParent() instanceof FrameLayout)
                || !dropdown.isShown()
                || dropdown.getWidth() <= 0) {
            if (attempt < 12) {
                bar.postDelayed(() -> tryShow(session, attempt + 1), 90);
            }
            return;
        }

        Page page = findPage(activity, session.displayBeforeFocus);
        if (page == null) {
            Log.i(TAG, "Current-page card: no verified active-tab HTTP URL; skipped");
            return;
        }

        try {
            session.dropdown = (ViewGroup) dropdown;
            session.suggestionContainer = (FrameLayout) dropdown.getParent();
            session.baselineTopPadding = dropdown.getPaddingTop();
            session.card = buildCard(activity, session, page);
            session.card.measure(
                    View.MeasureSpec.makeMeasureSpec(dropdown.getWidth(),
                            View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            session.cardHeight = session.card.getMeasuredHeight();
            if (session.cardHeight <= 0) {
                session.card = null;
                return;
            }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, session.cardHeight, Gravity.TOP);
            lp.topMargin = dropdown.getTop() + session.baselineTopPadding;
            session.suggestionContainer.addView(session.card, lp);
            dropdown.setPaddingRelative(
                    dropdown.getPaddingStart(),
                    session.baselineTopPadding + session.cardHeight,
                    dropdown.getPaddingEnd(), dropdown.getPaddingBottom());
            session.card.setElevation(dp(activity, 1));
            session.card.bringToFront();

            // Chrome can update OmniboxAlignment.paddingTop after the first
            // suggestion list has been shown (IME and bottom/top toolbar
            // transitions). Reapply the row inset when Chromium replaces
            // RecyclerView's padding, and follow its measured dropdown top.
            // No native suggestion adapter or scroll position is modified.
            session.layoutObserver = () -> {
                if (session.card == null || session.dropdown == null
                        || !session.urlBar.hasFocus()) return true;
                int actualPadding = session.dropdown.getPaddingTop();
                int expectedPadding = session.baselineTopPadding + session.cardHeight;
                if (actualPadding != expectedPadding
                        && actualPadding < session.cardHeight) {
                    session.baselineTopPadding = actualPadding;
                    session.dropdown.setPaddingRelative(
                            session.dropdown.getPaddingStart(),
                            actualPadding + session.cardHeight,
                            session.dropdown.getPaddingEnd(),
                            session.dropdown.getPaddingBottom());
                }
                ViewGroup.LayoutParams base = session.card.getLayoutParams();
                if (base instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams layout =
                            (FrameLayout.LayoutParams) base;
                    int wantedTop = session.dropdown.getTop()
                            + session.baselineTopPadding;
                    if (layout.topMargin != wantedTop) {
                        layout.topMargin = wantedTop;
                        session.card.setLayoutParams(layout);
                    }
                }
                return true;
            };
            session.dropdown.getViewTreeObserver()
                    .addOnPreDrawListener(session.layoutObserver);
            session.suggestionContainer.requestLayout();
            Log.i(TAG, "Current-page card: shown, heightPx=" + session.cardHeight
                    + " (URL from active Chrome tab)");
        } catch (Throwable e) {
            Log.w(TAG, "Current-page card insertion failed", e);
            removeCard(session);
        }
    }

    private static LinearLayout buildCard(Activity activity, CardSession session, Page page) {
        int primary = themeColor(activity, android.R.attr.textColorPrimary, 0xff202124);
        int secondary = themeColor(activity, android.R.attr.textColorSecondary, 0xff5f6368);
        int background = themeColor(activity, android.R.attr.colorBackground, 0xffffffff);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 16), dp(activity, 12),
                dp(activity, 16), dp(activity, 8));
        card.setBackgroundColor(background);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        ImageView favicon = new ImageView(activity);
        if (page.favicon != null) {
            favicon.setImageBitmap(page.favicon);
        } else {
            favicon.setImageResource(android.R.drawable.ic_menu_view);
            favicon.setImageTintList(ColorStateList.valueOf(secondary));
        }
        heading.addView(favicon, new LinearLayout.LayoutParams(
                dp(activity, 24), dp(activity, 24)));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(activity, 12);
        TextView title = new TextView(activity);
        title.setText(page.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(primary);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView url = new TextView(activity);
        url.setText(page.url);
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        url.setTextColor(secondary);
        url.setSingleLine(true);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(url);
        heading.addView(texts, textLp);
        heading.setOnClickListener(v -> {
            // The native page card opens the current suggestion. The page is
            // already active; simply clear the focused editor.
            session.urlBar.clearFocus();
        });
        card.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        addAction(activity, actions, "share", "共有", "Share",
                "ic_share_white_24dp", android.R.drawable.ic_menu_share,
                secondary, v -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, page.url);
                    activity.startActivity(Intent.createChooser(send, null));
                });
        addAction(activity, actions, "copy", "コピー", "Copy",
                "ic_content_copy", android.R.drawable.ic_menu_save,
                secondary, v -> {
                    ClipboardManager clipboard = (ClipboardManager)
                            activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("URL", page.url));
                    }
                });
        addAction(activity, actions, "edit", "編集", "Edit",
                "ic_edit_24dp", android.R.drawable.ic_menu_edit,
                secondary, v -> {
                    if (session.urlBar instanceof EditText) {
                        EditText edit = (EditText) session.urlBar;
                        edit.setText(page.url);
                        edit.setSelection(edit.length());
                    }
                });
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)));
        return card;
    }

    private static void addAction(Activity activity, LinearLayout parent,
            String key, String japanese, String english,
            String chromeDrawable, int defaultDrawable, int tint,
            View.OnClickListener click) {
        final boolean ja = activity.getResources()
                .getConfiguration().getLocales().get(0)
                .getLanguage().equals("ja");
        LinearLayout button = new LinearLayout(activity);
        button.setGravity(Gravity.CENTER);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setContentDescription(ja ? japanese : english);
        button.setClickable(true);
        button.setFocusable(true);
        TypedValue out = new TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, out, true)
                && out.resourceId != 0) {
            button.setBackgroundResource(out.resourceId);
        }
        int drawable = activity.getResources().getIdentifier(
                chromeDrawable, "drawable", TARGET_PACKAGE);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(drawable != 0 ? drawable : defaultDrawable);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        button.addView(icon, new LinearLayout.LayoutParams(
                dp(activity, 20), dp(activity, 20)));
        TextView label = new TextView(activity);
        label.setText(ja ? japanese : english);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(tint);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.leftMargin = dp(activity, 5);
        button.addView(label, l);
        button.setOnClickListener(click);
        parent.addView(button, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private static Page findPage(Activity activity, String displayUrl) {
        Object tab = findActiveTab(activity);
        if (tab == null) return null;
        String url = getTabUrl(tab, displayUrl);
        if (url == null) return null;
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        String title = callString(tab, "getTitle");
        if (TextUtils.isEmpty(title)) title = parsed.getHost();
        if (TextUtils.isEmpty(title)) return null;
        Bitmap favicon = callBitmap(tab, "getFavicon");
        return new Page(url, title, favicon);
    }

    private static Object findActiveTab(Activity activity) {
        // First try Chromium's ChromeActivity#getActivityTab API, if R8 kept
        // its name. ChromeActivity sits in the superclass chain of the
        // manifest-registered ChromeTabbedActivity.
        for (Class<?> cls = activity.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Method method = cls.getDeclaredMethod("getActivityTab");
                if (method.getParameterCount() != 0) continue;
                method.setAccessible(true);
                Object tab = method.invoke(activity);
                if (tab != null) return tab;
            } catch (Throwable ignored) {}
        }

        // If the method is R8-renamed, locate the ActivityTabProvider using
        // Java's stable java.util.function.Supplier interface. Verify the
        // returned object exposes a genuine Chrome GURL before accepting it.
        for (Class<?> cls = activity.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !Supplier.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object supplier = field.get(activity);
                    if (!(supplier instanceof Supplier)) continue;
                    Object candidate = ((Supplier<?>) supplier).get();
                    if (candidate != null && hasGurlGetter(candidate.getClass())) {
                        return candidate;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean hasGurlGetter(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (m.getParameterCount() == 0
                    && m.getReturnType().getName().equals("org.chromium.url.GURL")) {
                return true;
            }
        }
        return false;
    }

    private static String getTabUrl(Object tab, String displayed) {
        try {
            Method exact = tab.getClass().getMethod("getUrl");
            Object value = exact.invoke(tab);
            String url = getGurlSpec(value);
            if (validUrl(url)) return url;
        } catch (Throwable ignored) {}

        // Structural fallback: inspect only zero-arg GURL-returning getters.
        // Some return the original/committed URL instead; reject an ambiguous
        // set rather than copying a potentially different page.
        List<String> candidates = new ArrayList<>();
        for (Method method : tab.getClass().getMethods()) {
            if (method.getParameterCount() != 0
                    || !method.getReturnType().getName()
                        .equals("org.chromium.url.GURL")) continue;
            try {
                String url = getGurlSpec(method.invoke(tab));
                if (validUrl(url) && !candidates.contains(url)) {
                    candidates.add(url);
                }
            } catch (Throwable ignored) {}
        }
        if (candidates.size() == 1) return candidates.get(0);
        if (candidates.size() > 1 && !TextUtils.isEmpty(displayed)) {
            String expected = displayed.replace("https://", "")
                    .replace("http://", "").replace("www.", "");
            List<String> matching = new ArrayList<>();
            for (String url : candidates) {
                String host = Uri.parse(url).getHost();
                if (host != null && expected.contains(host.replace("www.", ""))) {
                    matching.add(url);
                }
            }
            if (matching.size() == 1) return matching.get(0);
        }
        return null;
    }

    private static String getGurlSpec(Object gurl) {
        if (gurl == null) return null;
        try {
            Method method = gurl.getClass().getMethod("getSpec");
            Object v = method.invoke(gurl);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}

        // JNI-backed GURL stores the normalized spec in a String field.
        for (Field field : gurl.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(gurl);
                if (value instanceof String && validUrl((String) value)) {
                    return (String) value;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean validUrl(String url) {
        if (url == null || url.length() < 10) return false;
        try {
            Uri uri = Uri.parse(url);
            return ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    && !TextUtils.isEmpty(uri.getHost());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String callString(Object object, String method) {
        try {
            Object result = object.getClass().getMethod(method).invoke(object);
            return result instanceof String ? (String) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap callBitmap(Object object, String method) {
        try {
            Object result = object.getClass().getMethod(method).invoke(object);
            return result instanceof Bitmap ? (Bitmap) result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Activity activityFor(Context context) {
        Context current = context;
        for (int i = 0; i < 12 && current != null; i++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) break;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static void removeCard(CardSession session) {
        try {
            if (session.dropdown != null && session.layoutObserver != null
                    && session.dropdown.getViewTreeObserver().isAlive()) {
                session.dropdown.getViewTreeObserver()
                        .removeOnPreDrawListener(session.layoutObserver);
            }
            session.layoutObserver = null;
            if (session.dropdown != null && session.card != null) {
                ViewGroup list = session.dropdown;
                int expected = session.baselineTopPadding + session.cardHeight;
                if (list.getPaddingTop() == expected) {
                    list.setPaddingRelative(list.getPaddingStart(),
                            session.baselineTopPadding,
                            list.getPaddingEnd(), list.getPaddingBottom());
                }
            }
            if (session.card != null && session.card.getParent() instanceof ViewGroup) {
                ((ViewGroup) session.card.getParent()).removeView(session.card);
            }
        } catch (Throwable e) {
            Log.w(TAG, "Current-page card cleanup failed", e);
        }
        session.card = null;
        session.dropdown = null;
        session.suggestionContainer = null;
        session.cardHeight = 0;
    }

    private static int themeColor(Context context, int attribute, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attribute, value, true)
                    && value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private CurrentPageCardFix() {}
}
