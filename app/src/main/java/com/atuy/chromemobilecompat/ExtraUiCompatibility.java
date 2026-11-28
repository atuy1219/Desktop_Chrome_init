package com.atuy.chromemobilecompat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import com.atuy.chromemobilecompat.compat.XC_MethodHook;
import com.atuy.chromemobilecompat.compat.XposedBridge;

/** Preserve Chrome's feature and native enterprise-policy checks; remove only its phone UI gate. */
final class ExtraUiCompatibility {
    private static final ThreadLocal<Integer> DEV_SCOPE = new ThreadLocal<>();
    private static boolean started, installed;
    static volatile String mostVisitedClass;
    static volatile String profileClass;
    static volatile String[] lensSymbols;

    static synchronized void start(Context app, ClassLoader loader, ApplicationInfo info) {
        if (started || app == null || loader == null || info == null) return;
        if (app.getResources().getIdentifier("extensions_menu_menu_id", "id",
                "com.android.chrome") == 0) return;
        started = true;
        try {
            installDevTools(loader, new String[]{
                    "org.chromium.chrome.browser.devtools.DevToolsWindowAndroid",
                    "isDevToolsAvailable", "org.chromium.ui.base.DeviceFormFactor",
                    "isNonMultiDisplayContextOnTablet"});
        } catch (Throwable ignored) {}
        SharedPreferences cache = app.getSharedPreferences("chromemobilecompat_extra_ui", 0);
        long version;
        try { version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode(); }
        catch (Throwable failure) { version = -1; }
        final long installedVersion = version;
        if (version >= 0 && cache.getLong("version", -2) == version
                && cache.getInt("schema", 0) == 2) {
            String[] data = new String[12];
            for (int i=0;i<data.length;i++) data[i] = cache.getString("symbol"+i,null);
            apply(loader, data);
            return;
        }
        new Thread(() -> {
            try {
                String[] data = ChromeDexResolver.resolveAdditionalUi(info);
                SharedPreferences.Editor edit = cache.edit().putLong("version", installedVersion)
                        .putInt("schema", 2);
                for (int i=0;i<data.length;i++) edit.putString("symbol"+i,data[i]);
                edit.apply();
                new Handler(Looper.getMainLooper()).post(() -> apply(loader, data));
            } catch (Throwable t) { XposedBridge.log("Extra UI discovery unavailable", t); }
        }, "ChromeExtraUiDiscovery").start();
    }

    private static void apply(ClassLoader loader, String[] data) {
        mostVisitedClass = data[0];
        profileClass = data[1];
        lensSymbols = data[6] == null ? null : java.util.Arrays.copyOfRange(data,6,12);
        if (!installed && data[2] != null) {
            try { installDevTools(loader, new String[]{data[2], data[3], data[4], data[5]}); }
            catch (Throwable e) { XposedBridge.log("DevTools gate hook unavailable", e); }
        }
        XposedBridge.log("Extra UI discovery: mostVisited=" + (mostVisitedClass != null)
                + " devToolsGate=" + installed + " lens=" + (lensSymbols != null));
        CurrentPageCardFix.refreshAfterFaviconResolved();
    }

    private static synchronized void installDevTools(ClassLoader loader, String[] names)
            throws Exception {
        if (installed) return;
        Class<?> gateOwner = Class.forName(names[0], false, loader);
        Method gate = null;
        try {gate=gateOwner.getDeclaredMethod(names[1],Context.class);}
        catch (NoSuchMethodException missing) {
            // R8 can inline isDevToolsAvailable into the native-policy wrapper.
            // Scope that wrapper but never replace its return value.
            for (Method m : gateOwner.getDeclaredMethods()) {
                Class<?>[] p=m.getParameterTypes();
                if (!names[1].equals(m.getName()) || p.length!=3 || p[0]!=Context.class
                        || p[1].isPrimitive() || p[2].isPrimitive()
                        || !Modifier.isStatic(m.getModifiers()) || m.getReturnType()!=boolean.class) continue;
                if(gate!=null) throw new IllegalStateException("ambiguous policy wrapper");
                gate=m;
            }
            if(gate==null)throw missing;
        }
        Method tablet = Class.forName(names[2], false, loader).getDeclaredMethod(names[3], Context.class);
        if (gate.equals(tablet) || gate.getReturnType()!=boolean.class
                || tablet.getReturnType()!=boolean.class || !Modifier.isStatic(gate.getModifiers())
                || !Modifier.isStatic(tablet.getModifiers())) throw new IllegalStateException("gate signature");
        // Hook the actual tablet predicate, not isDevToolsAllowedFor().
        // Do not change unrelated form factor checks or turn on a disabled feature.
        XposedBridge.hookMethod(tablet, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Integer depth = DEV_SCOPE.get();
                if (depth == null || depth == 0 || !(param.args[0] instanceof Context)) return;
                Context ctx = (Context) param.args[0];
                int width = ctx.getResources().getConfiguration().smallestScreenWidthDp;
                if (width > 0 && width < 600
                        && !ctx.getPackageManager().hasSystemFeature("android.hardware.type.pc"))
                    param.setResult(true);
            }
        });
        XposedBridge.hookMethod(gate, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Integer depth = DEV_SCOPE.get();
                DEV_SCOPE.set(depth == null ? 1 : depth + 1);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Integer depth = DEV_SCOPE.get();
                if (depth == null || depth <= 1) DEV_SCOPE.remove();
                else DEV_SCOPE.set(depth - 1);
            }
        });
        installed = true;
        XposedBridge.log("DevTools: scoped tablet gate installed; feature/native policy retained");
    }
    private ExtraUiCompatibility() {}
}
