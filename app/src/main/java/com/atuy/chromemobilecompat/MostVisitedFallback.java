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
            if(observe==null || destroy==null) return;
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
