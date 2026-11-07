package com.atuy.desktopchromeinit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
    private static final String TAG = "DesktopChromeInit";
    private static final String PREF = "desktopchromeinit_ui_discovery";
    private static final String KEY_VERSION = "chromeVersionCode";
    private static final String KEY_FAV_CLASS = "faviconClass";
    private static final String KEY_FAV_METHOD = "faviconMethod";

    private static volatile String faviconClass;
    private static volatile String faviconMethod;
    private static volatile boolean starting;

    static synchronized void start(Context context) {
        if (starting) return;
        if (!"com.android.chrome".equals(context.getPackageName())) return;
        starting = true;

        final Context application = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final SharedPreferences prefs = application.getSharedPreferences(
                PREF, Context.MODE_PRIVATE);

        final long version;
        try {
            PackageInfo info = application.getPackageManager()
                    .getPackageInfo(application.getPackageName(), 0);
            version = info.getLongVersionCode();
        } catch (Throwable ex) {
            Log.w(TAG, "UI discovery: cannot inspect installed Chrome version", ex);
            starting = false;
            return;
        }

        if (prefs.getLong(KEY_VERSION, -1) == version) {
            faviconClass = prefs.getString(KEY_FAV_CLASS, null);
            faviconMethod = prefs.getString(KEY_FAV_METHOD, null);
            Log.i(TAG, "UI discovery: cached Chrome version=" + version
                    + " faviconAccessor=" + (faviconMethod != null)
                    + " (no page/clipboard data)");
            return;
        }

        Log.i(TAG, "UI discovery: Chrome version changed, scan started on worker");
        final ApplicationInfo appInfo = application.getApplicationInfo();
        new Thread(() -> {
            String[] favicon = null;
            try {
                favicon = ChromeDexResolver.findFaviconAccessor(appInfo);
                faviconClass = favicon[0];
                faviconMethod = favicon[1];
            } catch (Throwable error) {
                Log.i(TAG, "UI discovery: favicon getter unavailable: "
                        + error.getClass().getSimpleName()
                        + "; will use non-obfuscated API if available");
            }

            // Save descriptors ONLY; no browsing data is stored in module prefs.
            prefs.edit().putLong(KEY_VERSION, version)
                    .putString(KEY_FAV_CLASS, favicon == null ? null : favicon[0])
                    .putString(KEY_FAV_METHOD, favicon == null ? null : favicon[1])
                    .apply();
            Log.i(TAG, "UI discovery: completed Chrome version=" + version
                    + " faviconAccessor=" + (favicon != null)
                    + " nativeOmniboxFactories=resolved-when-dropdown-attaches");
        }, "ChromeUiDiscovery").start();
    }

    static Bitmap currentFavicon(Object tab, ClassLoader loader) {
        if (tab == null || loader == null) return null;
        // The official upstream TabFavicon.getBitmapWithFallback(Tab, true)
        // returns the current tab's favicon, falling back to the local DB.
        // Direct symbol path is tried first (e.g. debug/less-obfuscated APK).
        try {
            Class<?> original = Class.forName(
                    "org.chromium.chrome.browser.tab.TabFavicon", false, loader);
            Bitmap bitmap = invokeBitmap(original, "getBitmapWithFallback", tab);
            if (bitmap != null) return bitmap;
        } catch (Throwable ignored) {}

        String className = faviconClass, methodName = faviconMethod;
        if (className == null || methodName == null) return null;
        try {
            Class<?> actual = Class.forName(className, false, loader);
            return invokeBitmap(actual, methodName, tab);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap invokeBitmap(Class<?> cls, String name, Object tab)
            throws ReflectiveOperationException {
        for (Method method : cls.getDeclaredMethods()) {
            if (!name.equals(method.getName())
                    || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != Bitmap.class) continue;
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 2 || p[1] != boolean.class
                    || !p[0].isInstance(tab)) continue;
            method.setAccessible(true);
            Object result = method.invoke(null, tab, true);
            if (result instanceof Bitmap && !((Bitmap) result).isRecycled()) {
                return (Bitmap) result;
            }
        }
        return null;
    }

    private ChromeUiDiscovery() {}
}
