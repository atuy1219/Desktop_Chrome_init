package com.atuy.chromemobilecompat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import com.atuy.chromemobilecompat.compat.XposedBridge;

/** Local Chrome NTP bridge fallback, not the missing native Omnibox provider. */
final class MostVisitedFallback {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_SITES = 6;
    private static final long SNAPSHOT_AGE_MS = 15 * 60 * 1000L;
    private static boolean callbackHooked;
    private static volatile Field sourceField;
    private static java.lang.ref.WeakReference<Object> capturedProfile =
            new java.lang.ref.WeakReference<>(null);
    private static List<Site> captured = Collections.emptyList();
    private static long capturedAt;

    static boolean captureReady(){return callbackHooked && sourceField!=null;}

    static void installNamedCapture(ClassLoader loader) {
        try {
            installCapture(loader,
                    "org.chromium.chrome.browser.suggestions.mostvisited.MostVisitedSitesBridge",
                    "onURLsAvailable",
                    "org.chromium.chrome.browser.suggestions.SiteSuggestion","source");
        }catch(Throwable ignored){}
    }

    static synchronized void installCapture(ClassLoader loader,String owner,String method,
            String siteClass,String source) {
        try {
            Class<?> cls=Class.forName(siteClass,false,loader);
            Field field=cls.getDeclaredField(source);
            if(field.getType()!=int.class || Modifier.isStatic(field.getModifiers()))return;
            field.setAccessible(true);
            sourceField=field;
            if(callbackHooked)return;
            Class<?> bridge=Class.forName(owner,false,loader);
            Method callback=bridge.getDeclaredMethod(method,boolean.class,List.class);
            if(Modifier.isStatic(callback.getModifiers()) || callback.getReturnType()!=void.class)return;
            XposedBridge.hookMethod(callback,new com.atuy.chromemobilecompat.compat.XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam param){
                    // The live NTP observer has a delegate with both the
                    // current Profile and a backreference to this very bridge.
                    // Do not assign a callback to whichever tab is focused.
                    if(!(param.args[1] instanceof List))return;
                    capture(param.thisObject,(List<?>)param.args[1]);
                }
            });
            callbackHooked=true;
            XposedBridge.log("Most visited: native NTP callback hook installed");
        }catch(Throwable failure){XposedBridge.log("Most visited callback unavailable",failure);}
    }

    private static Object profileFromBridge(Object bridge) throws Exception{
        Object found=null;
        for(Field observerField:bridge.getClass().getDeclaredFields()){
            if(Modifier.isStatic(observerField.getModifiers()) || observerField.getType().isPrimitive()
                    || observerField.getType().getName().startsWith("java."))continue;
            observerField.setAccessible(true);
            Object observer=observerField.get(bridge);
            if(observer==null)continue;
            for(Field delegateField:observer.getClass().getDeclaredFields()){
                if(Modifier.isStatic(delegateField.getModifiers()) || delegateField.getType().isPrimitive()
                        || delegateField.getType().getName().startsWith("java.")
                        || delegateField.getType().getName().startsWith("android."))continue;
                Object delegate;
                try{delegateField.setAccessible(true);delegate=delegateField.get(observer);}
                catch(Throwable ignored){continue;}
                if(delegate==null)continue;
                Object profile=null;
                boolean sameBridge=false;
                for(Field f:delegate.getClass().getDeclaredFields()){
                    if(Modifier.isStatic(f.getModifiers()))continue;
                    if(f.getType()==bridge.getClass()){
                        f.setAccessible(true);sameBridge|=f.get(delegate)==bridge;
                    }
                    if("org.chromium.chrome.browser.profiles.Profile".equals(f.getType().getName())){
                        f.setAccessible(true);profile=f.get(delegate);
                    }
                }
                if(sameBridge && profile!=null){
                    if(found!=null && found!=profile)return null;
                    found=profile;
                }
            }
        }
        return found;
    }

    private static void capture(Object bridge,List<?> list){
        try{
            Object profile=profileFromBridge(bridge);
            if(profile==null || !Boolean.FALSE.equals(profile.getClass()
                    .getMethod("isOffTheRecord").invoke(profile)))return;
            List<Site> sites=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for(Object item:list){
                Site site=site(item,null,null);
                if(site!=null && seen.add(site.url))sites.add(site);
                if(sites.size()>=MAX_SITES)break;
            }
            synchronized(MostVisitedFallback.class){
                capturedProfile=new java.lang.ref.WeakReference<>(profile);
                captured=sites;
                capturedAt=android.os.SystemClock.elapsedRealtime();
            }
            XposedBridge.log("Most visited: captured local NTP personal tiles="+sites.size());
            MAIN.post(CurrentPageCardFix::refreshAfterFaviconResolved);
        }catch(Throwable failure){XposedBridge.log("Most visited capture unavailable",failure);}
    }

    private static synchronized List<Site> snapshot(Object profile){
        if(capturedProfile.get()!=profile || android.os.SystemClock.elapsedRealtime()-capturedAt
                > SNAPSHOT_AGE_MS)return Collections.emptyList();
        return new ArrayList<>(captured);
    }
    private static final class Site {
        final String title, url;
        final Object gurl;
        Site(String title, String url, Object gurl) {
            this.title = title; this.url = url; this.gurl = gurl;
        }
    }
    static boolean nativePresent(ViewGroup dropdown) {
        try {
            Object adapter = dropdown.getClass().getMethod("getAdapter").invoke(dropdown);
            if (adapter == null) return false;
            Method count = adapter.getClass().getMethod("getItemCount");
            Method type = adapter.getClass().getMethod("getItemViewType",int.class);
            for (int i=0,n=(Integer)count.invoke(adapter);i<Math.min(n,40);i++)
                if ((Integer)type.invoke(adapter,i)==7) return true; // TILE_NAVSUGGEST
        } catch (Throwable ignored) {}
        return false;
    }
    static void request(Activity activity, ViewGroup dropdown, Object tab, TextView bar,
            LinearLayout destination, BooleanSupplier active, Runnable onChanged) {
        if (tab == null || !active.getAsBoolean() || nativePresent(dropdown)) return;
        Object bridge = null;
        Method destroy = null;
        try {
            ClassLoader loader = tab.getClass().getClassLoader();
            String name = ExtraUiCompatibility.mostVisitedClass;
            Class<?> owner = Class.forName(name != null ? name :
                    "org.chromium.chrome.browser.suggestions.mostvisited.MostVisitedSitesBridge",
                    false, loader);
            Class<?> profileType = Class.forName(ExtraUiCompatibility.profileClass != null
                    ? ExtraUiCompatibility.profileClass :
                    "org.chromium.chrome.browser.profiles.Profile", false, loader);
            Method profileGetter = null;
            for (Method m : tab.getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount()!=0
                        || m.getReturnType()!=profileType) continue;
                if ("getProfile".equals(m.getName())) {profileGetter=m;break;}
                if (profileGetter!=null) return;
                profileGetter=m;
            }
            if (profileGetter==null) return;
            profileGetter.setAccessible(true);
            Object profile=profileGetter.invoke(tab);
            if (profile==null) return;
            List<Site> saved=snapshot(profile);
            if(!saved.isEmpty()){
                render(activity,dropdown,tab,bar,destination,saved,active);
                onChanged.run();
                XposedBridge.log("Most visited fallback: reused local NTP tiles="+saved.size());
                return;
            }
            Method observe=null, score=null;
            List<Method> cleanup=new ArrayList<>();
            for (Method m:owner.getDeclaredMethods()) {
                Class<?>[] p=m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType()==void.class && p.length==2 && p[0].isInterface() && p[1]==int.class) {
                    boolean list=false, icon=false;
                    for (Method cb:p[0].getMethods()) {
                        Class<?>[] cp=cb.getParameterTypes();
                        if (cb.getReturnType()!=void.class) continue;
                        list |= cp.length==2 && cp[0]==boolean.class && List.class.isAssignableFrom(cp[1]);
                        icon |= cp.length==1 && "org.chromium.url.GURL".equals(cp[0].getName());
                    }
                    if (list && icon) { if(observe!=null)return; observe=m; }
                }
                if (m.getReturnType()==void.class && p.length==0) {
                    cleanup.add(m);
                    if ("destroy".equals(m.getName())) destroy=m;
                }
                if (m.getReturnType()==double.class && p.length==1 &&
                        "org.chromium.url.GURL".equals(p[0].getName())) {
                    if(score!=null)return; score=m;
                }
            }
            if(destroy==null && cleanup.size()==1) destroy=cleanup.get(0);
            if(observe==null || destroy==null) {
                XposedBridge.log("Most visited: bridge methods inlined; waiting for native NTP callback");
                return;
            }
            Constructor<?> ctor=owner.getDeclaredConstructor(profileType);
            ctor.setAccessible(true);
            observe.setAccessible(true);
            destroy.setAccessible(true);
            if(score!=null)score.setAccessible(true);
            bridge=ctor.newInstance(profile);
            final Object instance=bridge;
            final Method releaseMethod=destroy, scoreMethod=score;
            final AtomicBoolean closed=new AtomicBoolean();
            Runnable release=()->{
                if(!closed.compareAndSet(false,true))return;
                try {releaseMethod.invoke(instance);}catch(Throwable ignored){}
            };
            destination.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View v){}
                @Override public void onViewDetachedFromWindow(View v){release.run();}
            });
            Class<?> cb=observe.getParameterTypes()[0];
            Object observer=Proxy.newProxyInstance(cb.getClassLoader(),new Class<?>[]{cb},(proxy,m,args)->{
                if(m.getDeclaringClass()==Object.class){
                    if("hashCode".equals(m.getName()))return System.identityHashCode(proxy);
                    if("equals".equals(m.getName()))return proxy==args[0];
                    return "ChromeMobileCompat most visited observer";
                }
                if(args!=null && args.length==2 && args[1] instanceof List){
                    List<?> list=new ArrayList<>((List<?>)args[1]);
                    MAIN.post(()->{
                        if(closed.get() || !active.getAsBoolean() || !destination.isAttachedToWindow()
                                || nativePresent(dropdown)){release.run();return;}
                        List<Site> sites=new ArrayList<>();
                        Set<String> seen=new HashSet<>();
                        for(Object item:list){
                            Site site=site(item,instance,scoreMethod);
                            if(site!=null && seen.add(site.url))sites.add(site);
                            if(sites.size()>=MAX_SITES)break;
                        }
                        release.run();
                        if(!sites.isEmpty()){
                            render(activity,dropdown,tab,bar,destination,sites,active);
                            onChanged.run();
                            XposedBridge.log("Most visited fallback: rows="+sites.size());
                        }
                    });
                }
                return null;
            });
            observe.invoke(instance,observer,MAX_SITES);
            MAIN.postDelayed(release,3000);
        } catch(Throwable e) {
            if(bridge!=null && destroy!=null)try{destroy.invoke(bridge);}catch(Throwable ignored){}
            XposedBridge.log("Most visited fallback unavailable",e);
        }
    }
    private static Site site(Object item,Object bridge,Method score){
        if(item==null)return null;
        try{
            Field title=null,url=null,source=null;
            for(Field f:item.getClass().getDeclaredFields()){
                if(Modifier.isStatic(f.getModifiers()))continue;
                if(f.getType()==String.class){if(title!=null)return null;title=f;}
                else if("org.chromium.url.GURL".equals(f.getType().getName())){if(url!=null)return null;url=f;}
                else if(f.getType()==int.class && "source".equals(f.getName()))source=f;
            }
            if(title==null || url==null)return null;
            title.setAccessible(true);url.setAccessible(true);
            Object gurl=url.get(item);
            String spec=CurrentPageCardFix.getGurlSpec(gurl);
            if(!CurrentPageCardFix.validUrl(spec))return null;
            // Use personal TopSites only, not promotional/popular/custom tiles.
            // TileSource.TOP_SITES = 0; when the enum field is obfuscated,
            // require the bridge's own valid local suggestion score.
            if(source==null && sourceField!=null &&
                    sourceField.getDeclaringClass().isInstance(item))source=sourceField;
            if(source!=null){source.setAccessible(true);if(source.getInt(item)!=0)return null;}
            else if(score==null || !(((Number)score.invoke(bridge,gurl)).doubleValue()>=0))return null;
            String label=(String)title.get(item);
            return new Site(TextUtils.isEmpty(label)?Uri.parse(spec).getHost():label,spec,gurl);
        }catch(Throwable ignored){return null;}
    }
    private static void render(Activity a,ViewGroup dropdown,Object tab,TextView bar,
            LinearLayout target,List<Site> sites,BooleanSupplier active){
        target.removeAllViews();
        FrameLayout surface=new FrameLayout(a);
        NativeOmniboxPageCard.applyChromeCardSurface(surface,a,null,false);
        HorizontalScrollView scroll=new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        surface.addView(scroll,new FrameLayout.LayoutParams(-1,-2));
        LinearLayout strip=new LinearLayout(a);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));
        scroll.addView(strip,new FrameLayout.LayoutParams(-2,-2));
        int color=bar.getCurrentTextColor();
        for(Site site:sites){
            LinearLayout tile=new LinearLayout(a);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setClickable(true);tile.setFocusable(true);
            ImageView icon=new ImageView(a);
            int edge=OmniboxIcons.pageIconSize(a);
            tile.addView(icon,new LinearLayout.LayoutParams(edge,edge));
            OmniboxIcons.pageDecoration(icon,null,color);
            TextView text=new TextView(a);
            text.setText(site.title);text.setTextColor(color);text.setTextSize(12);
            text.setGravity(Gravity.CENTER);text.setMaxLines(2);
            text.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.topMargin=dp(a,6);
            tile.addView(text,lp);
            tile.setContentDescription(site.title);
            tile.setOnClickListener(v->{
                if(!active.getAsBoolean())return;
                if(bar instanceof android.widget.EditText){
                    android.widget.EditText edit=(android.widget.EditText)bar;
                    edit.setText(site.url);edit.setSelection(edit.length());
                    edit.onEditorAction(EditorInfo.IME_ACTION_GO);
                }
            });
            strip.addView(tile,new LinearLayout.LayoutParams(dp(a,88),dp(a,76)));
            ChromeUiDiscovery.requestSiteFavicon(tab,a.getClassLoader(),site.gurl,edge,
                    bitmap->{if(active.getAsBoolean()&&icon.isAttachedToWindow())OmniboxIcons.favicon(icon,bitmap);});
        }
        target.addView(surface,new LinearLayout.LayoutParams(-1,-2));
        target.setVisibility(View.VISIBLE);
    }
    private static int dp(Context c,int n){return Math.round(c.getResources().getDisplayMetrics().density*n);}
    private MostVisitedFallback(){}
}
