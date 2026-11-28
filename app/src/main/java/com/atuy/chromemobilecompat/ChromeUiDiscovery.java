package com.atuy.chromemobilecompat;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import com.atuy.chromemobilecompat.compat.XposedBridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;

/**
 * Chrome package-version-keyed discovery for R8-obfuscated Omnibox helpers.
 *
 * The source build and class names may differ from release to release. Do the
 * expensive DEX scan after the installed APK version changes, on a worker,
 * persist only class/method descriptors, and validate before invoking.
 *
 * Never log the page URL, clipboard content, or user data. Never scan the
 * clipboard during startup.
 */
final class ChromeUiDiscovery {
    private static final String TAG = "ChromeMobileCompat";
    private static final String PREF = "chromemobilecompat_ui_discovery";
    private static final String KEY_VERSION = "chromeVersionCode";
    private static final String KEY_SCHEMA = "uiDiscoverySchema";
    // Increment when the module changes discovery logic. Re-scan on the
    // next Chrome start even if the official Chrome version stays the same.
    private static final int DISCOVERY_SCHEMA = 4;
    private static final String KEY_FAV_CLASS = "faviconClass";
    private static final String KEY_FAV_METHOD = "faviconMethod";

    private static volatile String faviconClass;
    private static volatile String faviconMethod;
    private static volatile String faviconFrom;
    private static volatile String faviconAsync;
    private static volatile String localClass, localMethod, localDestroy;
    private static volatile boolean starting;

    static synchronized void start(Context context) {
        if (starting) return;
        if (!"com.android.chrome".equals(context.getPackageName())) return;
        starting = true;

        final Context application = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final SharedPreferences prefs = application.getSharedPreferences(
                PREF, Context.MODE_PRIVATE);

        // Stable Android resource identifiers are the strongest anchors for
        // Chrome's R8-independent Omnibox UI. Survey only public identifiers
        // at startup; never instantiate internal models during startup.
        String[] requiredIds = {
                "omnibox_suggestions_dropdown", "line_1", "line_2"
        };
        String[] requiredDrawables = {
                "ic_share_white_24dp", "ic_content_copy",
                "ic_edit_24dp", "ic_visibility_black", "ic_globe_24dp"
        };
        String[] requiredDimens = {
                "omnibox_suggestion_side_spacing_smallest",
                "omnibox_suggestion_content_height",
                "omnibox_suggestion_action_button_width",
                "omnibox_suggestion_bg_round_corner_radius"
        };
        reportResourceCategory(application, "id", requiredIds);
        reportResourceCategory(application, "drawable", requiredDrawables);
        reportResourceCategory(application, "dimen", requiredDimens);

        final long version;
        try {
            PackageInfo info = application.getPackageManager()
                    .getPackageInfo(application.getPackageName(), 0);
            version = info.getLongVersionCode();
        } catch (Throwable ex) {
            XposedBridge.log("UI discovery: cannot inspect installed Chrome version", ex);
            starting = false;
            return;
        }

        if (prefs.getLong(KEY_VERSION, -1) == version
                && prefs.getInt(KEY_SCHEMA, -1) == DISCOVERY_SCHEMA
                && (prefs.getString(KEY_FAV_METHOD, null) != null
                    || prefs.getString("localMethod", null) != null)) {
            localClass = prefs.getString("localClass", null);
            localMethod = prefs.getString("localMethod", null);
            localDestroy = prefs.getString("localDestroy", null);
            faviconClass = prefs.getString(KEY_FAV_CLASS, null);
            faviconFrom = prefs.getString("faviconFrom", null);
            faviconAsync = prefs.getString("faviconAsync", null);
            faviconMethod = prefs.getString(KEY_FAV_METHOD, null);
            XposedBridge.log("UI discovery: cached Chrome version=" + version
                    + " faviconAccessor=" + (faviconMethod != null)
                    + " (no page/clipboard data)");
            return;
        }

        XposedBridge.log("UI discovery: Chrome version changed, scan started on worker");
        final ApplicationInfo appInfo = application.getApplicationInfo();
        new Thread(() -> {
            String[] favicon = null;
            try {
                favicon = ChromeDexResolver.findFaviconAccessor(appInfo);
                faviconClass = favicon[0];
                faviconFrom = favicon[3];
                faviconAsync = favicon[4];
                faviconMethod = favicon[1];
                localClass = favicon[5];
                localMethod = favicon[6];
                localDestroy = favicon[7];
            } catch (Throwable error) {
                XposedBridge.log("UI discovery: favicon getter unavailable: "
                        + error.getClass().getSimpleName()
                        + "; will use non-obfuscated API if available");
            }

            // Save descriptors ONLY; no browsing data is stored in module prefs.
            prefs.edit().putLong(KEY_VERSION, version)
                    .putInt(KEY_SCHEMA, DISCOVERY_SCHEMA)
                    .putString(KEY_FAV_CLASS, favicon == null ? null : favicon[0])
                    .putString(KEY_FAV_METHOD, favicon == null ? null : favicon[1])
                    .putString("faviconFrom", favicon == null ? null : favicon[3])
                    .putString("faviconAsync", favicon == null ? null : favicon[4])
                    .putString("localClass", favicon == null ? null : favicon[5])
                    .putString("localMethod", favicon == null ? null : favicon[6])
                    .putString("localDestroy", favicon == null ? null : favicon[7])
                    .apply();
            XposedBridge.log("UI discovery: completed Chrome version=" + version
                    + " faviconAccessor=" + (favicon != null)
                    + " asyncFavicon=" + (faviconAsync != null)
                    + " localDb=" + (localMethod != null)
                    + " nativeOmniboxFactories=resolved-when-dropdown-attaches");
            if (favicon != null) {
                new Handler(Looper.getMainLooper()).post(
                        CurrentPageCardFix::refreshAfterFaviconResolved);
            }
        }, "ChromeUiDiscovery").start();
    }

    private static void reportResourceCategory(
            Context ctx, String type, String[] names) {
        int available = 0;
        for (String name : names) {
            if (ctx.getResources().getIdentifier(
                    name, type, "com.android.chrome") != 0) {
                available++;
            } else {
                XposedBridge.log("UI discovery: resource absent " + type + "/" + name);
            }
        }
        XposedBridge.log("UI discovery: resource type=" + type
                + " resolved=" + available + "/" + names.length);
    }

    private static Class<?> faviconOwner(Object tab, ClassLoader loader) {
        ClassLoader tabLoader = tab.getClass().getClassLoader();
        String[] names = {faviconClass, "org.chromium.chrome.browser.tab.TabFavicon"};
        for (String name : names) {
            if (name == null) continue;
            for (ClassLoader candidate : new ClassLoader[]{tabLoader, loader}) {
                if (candidate == null) continue;
                try { return Class.forName(name, false, candidate); }
                catch (Throwable ignored) {}
            }
        }
        return null;
    }

    static Bitmap currentFavicon(Object tab, ClassLoader loader) {
        if (tab == null) return null;
        Class<?> owner = faviconOwner(tab, loader);
        if (owner == null) return null;
        try {
            String name = faviconMethod == null ? "getBitmapWithFallback" : faviconMethod;
            return invokeBitmap(owner, name, tab);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void requestFavicon(Object tab, ClassLoader loader, Object gurl,
            int size, Consumer<Bitmap> result) {
        if (tab == null) return;
        if (requestLocalFavicon(tab, loader, gurl, size, result)) return;
        requestTabFavicon(tab, loader, result);
    }

    static void requestSiteFavicon(Object tab, ClassLoader loader, Object gurl,
            int size, Consumer<Bitmap> result) {
        requestLocalFavicon(tab, loader, gurl, size, result, false);
    }

    private static boolean requestLocalFavicon(Object tab, ClassLoader loader, Object gurl,
            int size, Consumer<Bitmap> result) {
        return requestLocalFavicon(tab, loader, gurl, size, result, true);
    }

    private static boolean requestLocalFavicon(Object tab, ClassLoader loader, Object gurl,
            int size, Consumer<Bitmap> result, boolean useTabFallback) {
        if (gurl == null) return false;
        Object helper = null;
        Method cleanup = null;
        try {
            String ownerName = localClass != null ? localClass :
                    "org.chromium.chrome.browser.ui.favicon.FaviconHelper";
            ClassLoader tabLoader = tab.getClass().getClassLoader();
            Class<?> owner = Class.forName(ownerName, false, tabLoader != null ? tabLoader : loader);
            String methodName = localMethod != null ? localMethod : "getLocalFaviconImageForURL";
            List<Method> methods = new ArrayList<>();
            for (Method m : owner.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(methodName) && !Modifier.isStatic(m.getModifiers())
                        && m.getReturnType() == boolean.class && p.length == 5
                        && p[1].isInstance(gurl) && p[2] == int.class && p[3] == boolean.class
                        && p[4].isInterface()) methods.add(m);
            }
            if (methods.size() != 1) return false;
            Method request = methods.get(0);
            Class<?>[] p = request.getParameterTypes();
            // Profile is taken only from this tab. Never fall back to a global
            // regular profile, particularly for incognito or custom tabs.
            List<Method> getters = new ArrayList<>();
            for (Method m : tab.getClass().getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && p[0].isAssignableFrom(m.getReturnType())) getters.add(m);
            }
            Method profileGetter = null;
            for (Method m : getters) if ("getProfile".equals(m.getName())) profileGetter = m;
            if (profileGetter == null && getters.size() == 1) profileGetter = getters.get(0);
            if (profileGetter == null) return false;
            profileGetter.setAccessible(true);
            Object profile = profileGetter.invoke(tab);
            if (profile == null || !p[0].isInstance(profile)) return false;
            int callbacks = 0;
            for (Method m : p[4].getMethods()) {
                if (!Modifier.isAbstract(m.getModifiers())) continue;
                Class<?>[] args = m.getParameterTypes();
                if (m.getReturnType() != void.class || args.length != 2
                        || args[0] != Bitmap.class || args[1] != p[1]) return false;
                callbacks++;
            }
            if (callbacks != 1) return false;
            cleanup = owner.getDeclaredMethod(localDestroy != null ? localDestroy : "destroy");
            if (Modifier.isStatic(cleanup.getModifiers()) || cleanup.getReturnType() != void.class)
                return false;
            cleanup.setAccessible(true);
            java.lang.reflect.Constructor<?> ctor = owner.getDeclaredConstructor();
            ctor.setAccessible(true);
            helper = ctor.newInstance();
            final Object instance = helper;
            final Method destroy = cleanup;
            final Handler main = new Handler(Looper.getMainLooper());
            final java.util.concurrent.atomic.AtomicBoolean finished =
                    new java.util.concurrent.atomic.AtomicBoolean();
            Runnable release = () -> {
                if (!finished.compareAndSet(false, true)) return;
                try { destroy.invoke(instance); }
                catch (Throwable ignored) {}
            };
            Object cb = Proxy.newProxyInstance(p[4].getClassLoader(), new Class<?>[]{p[4]},
                    (proxy, m, args) -> {
                        if (m.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(m.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(m.getName())) return proxy == args[0];
                            return "ChromeMobileCompat local favicon callback";
                        }
                        Bitmap image = args != null && args.length == 2 && args[0] instanceof Bitmap
                                ? (Bitmap) args[0] : null;
                        main.post(() -> {
                            if (finished.get()) return;
                            release.run();
                            if (OmniboxIcons.valid(image)) result.accept(image);
                            else if (useTabFallback) requestTabFavicon(tab, loader, result);
                        });
                        return null;
                    });
            request.setAccessible(true);
            boolean accepted = Boolean.TRUE.equals(request.invoke(instance, profile, gurl,
                    size, false, cb));
            if (!accepted) { release.run(); return false; }
            main.postDelayed(release, 5000);
            XposedBridge.log("Favicon: direct Chrome local DB request accepted");
            return true;
        } catch (Throwable failure) {
            if (helper != null && cleanup != null) {
                try { cleanup.invoke(helper); } catch (Throwable ignored) {}
            }
            XposedBridge.log("Favicon: direct local DB path unavailable", failure);
            return false;
        }
    }

    private static void requestTabFavicon(Object tab, ClassLoader loader, Consumer<Bitmap> result) {
        if (tab == null) return;
        try {
            Class<?> owner = faviconOwner(tab, loader);
            if (owner == null) return;
            // TabFavicon.getBitmapWithFallback only checks an EXISTING user-data
            // instance. Use the real from(Tab) entry point to create it if absent.
            String from = faviconFrom == null ? "from" : faviconFrom;
            Object helper = null;
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(from) || !Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != owner || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isInstance(tab)) continue;
                method.setAccessible(true);
                helper = method.invoke(null, tab);
                break;
            }
            Bitmap bitmap = currentFavicon(tab, loader);
            if (OmniboxIcons.valid(bitmap)) { result.accept(bitmap); return; }
            if (helper == null) return;
            String async = faviconAsync == null ? "getFaviconOrFallback" : faviconAsync;
            Method request = owner.getDeclaredMethod(async);
            if (Modifier.isStatic(request.getModifiers()) || request.getReturnType().isPrimitive()) return;
            request.setAccessible(true);
            Object promise = request.invoke(helper);
            if (promise == null) return;
            // Chromium Promise.then(Callback, Callback), with R8-independent
            // validation of the single-method callback interface.
            List<Method> candidates = new ArrayList<>();
            for (Method method : promise.getClass().getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                        || p.length != 2 || p[0] != p[1] || !p[0].isInterface()) continue;
                int callbacks = 0;
                for (Method cb : p[0].getMethods()) {
                    if (Modifier.isAbstract(cb.getModifiers()) && cb.getReturnType() == void.class
                            && cb.getParameterCount() == 1) callbacks++;
                }
                if (callbacks == 1) candidates.add(method);
            }
            Method then = null;
            for (Method method : candidates) if (method.getName().equals("then")) then = method;
            if (then == null && candidates.size() == 1) then = candidates.get(0);
            if (then == null) {
                XposedBridge.log("Favicon: local DB request started; Promise callback unresolved");
                return;
            }
            Class<?> callback = then.getParameterTypes()[0];
            Object success = callback(callback, value -> {
                if (value instanceof Bitmap && OmniboxIcons.valid((Bitmap) value))
                    result.accept((Bitmap) value);
            });
            Object failure = callback(callback, value -> {});
            then.setAccessible(true);
            then.invoke(promise, success, failure);
            XposedBridge.log("Favicon: Chrome local DB callback registered");
        } catch (Throwable t) {
            XposedBridge.log("Favicon: local DB callback unavailable", t);
        }
    }

    private static Object callback(Class<?> type, Consumer<Object> consumer) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return "ChromeMobileCompat favicon callback";
                    }
                    if (args != null && args.length == 1) consumer.accept(args[0]);
                    return null;
                });
    }

    private static Bitmap invokeBitmap(Class<?> cls, String name, Object tab)
            throws ReflectiveOperationException {
        for (Method method : cls.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != Bitmap.class) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 2 || p[1] != boolean.class || !p[0].isInstance(tab)) continue;
            method.setAccessible(true);
            Object result = method.invoke(null, tab, true);
            if (result instanceof Bitmap && OmniboxIcons.valid((Bitmap) result)) return (Bitmap) result;
        }
        return null;
    }

    private ChromeUiDiscovery() {}
}
