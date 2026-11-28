package com.atuy.chromemobilecompat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.Toast;
import java.io.InputStream;
import java.lang.reflect.Method;
import com.atuy.chromemobilecompat.compat.XposedBridge;

/** Image preview is decoded only after reveal. Search is explicit Chrome Lens action. */
final class ClipboardImageSupport {
    static Bitmap preview(Context c,Uri uri,int edge) {
        try {
            BitmapFactory.Options o=new BitmapFactory.Options();
            o.inJustDecodeBounds=true;
            try(InputStream in=c.getContentResolver().openInputStream(uri)){
                BitmapFactory.decodeStream(in,null,o);
            }
            if(o.outWidth<=0 || o.outHeight<=0)return null;
            o.inSampleSize=1;
            while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>Math.max(256,edge*2))
                o.inSampleSize*=2;
            o.inJustDecodeBounds=false;
            try(InputStream in=c.getContentResolver().openInputStream(uri)){
                return BitmapFactory.decodeStream(in,null,o);
            }
        }catch(Throwable ignored){return null;}
    }
    static void search(Activity activity,Uri uri) {
        try{
            ClassLoader loader=activity.getClassLoader();
            String[] symbols=ExtraUiCompatibility.lensSymbols;
            if(symbols==null)symbols=new String[]{
                    "org.chromium.chrome.browser.lens.LensController","getInstance","startLens",
                    "org.chromium.chrome.browser.lens.LensIntentParams$Builder","withImageUri","build"};
            Class<?> controller=Class.forName(symbols[0],false,loader);
            Class<?> builder=Class.forName(symbols[3],false,loader);
            java.lang.reflect.Constructor<?> ctor=builder.getDeclaredConstructor(int.class,boolean.class);
            Method withImage=builder.getDeclaredMethod(symbols[4],Uri.class);
            Method build=builder.getDeclaredMethod(symbols[5]);
            Method get=controller.getDeclaredMethod(symbols[1]);
            Method launch=controller.getDeclaredMethod(symbols[2],Context.class,build.getReturnType());
            if(withImage.getReturnType()!=builder || get.getReturnType()!=controller
                    || !java.lang.reflect.Modifier.isStatic(get.getModifiers())
                    || launch.getReturnType()!=void.class || build.getReturnType().isPrimitive())
                throw new IllegalStateException("Lens signature mismatch");
            ctor.setAccessible(true);withImage.setAccessible(true);build.setAccessible(true);
            get.setAccessible(true);launch.setAccessible(true);
            // Source enum CONTEXT_MENU_SEARCH_MENU_ITEM = 0; no native
            // clipboard Lens entry point. Use Chrome's existing image query.
            Object b=ctor.newInstance(0,false);
            Object configured=withImage.invoke(b,uri);
            Object p=build.invoke(configured);
            Object instance=get.invoke(null);
            launch.invoke(instance,activity,p);
            XposedBridge.log("Clipboard image: Chrome Lens requested");
        }catch(ReflectiveOperationException | LinkageError unavailable){
            XposedBridge.log("Clipboard image: Chrome Lens entry unavailable",unavailable);
            if(!(unavailable instanceof java.lang.reflect.InvocationTargetException)
                    && ExtraUiCompatibility.lensIntentProtocol && launchLensIntent(activity,uri))return;
            Toast.makeText(activity,"画像検索を開始できませんでした",Toast.LENGTH_SHORT).show();
        }catch(Throwable error){
            XposedBridge.log("Clipboard image: Chrome Lens launch failed",error);
            Toast.makeText(activity,"画像検索を開始できませんでした",Toast.LENGTH_SHORT).show();
        }
    }
    // Fallback only after explicit user tap and only when the installed DEX
    // still contains Chrome's exact Google Lens intent protocol. No R8 IDs.
    // Android grants the receiver temporary access to this one selected URI.
    private static boolean launchLensIntent(Activity activity,Uri uri){
        if(uri==null || !"content".equals(uri.getScheme()) || !activity.hasWindowFocus())return false;
        try{
            Uri data=Uri.parse("googleapp://lens").buildUpon()
                    .appendQueryParameter("LensBitmapUriKey",uri.toString())
                    .appendQueryParameter("AccountNameUriKey","")
                    .appendQueryParameter("IncognitoUriKey","false")
                    .appendQueryParameter("ActivityLaunchTimestampNanos",
                            Long.toString(android.os.SystemClock.elapsedRealtimeNanos()))
                    .build();
            android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_VIEW,data)
                    .setPackage("com.google.android.googlequicksearchbox")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Image",uri));
            activity.startActivity(intent);
            XposedBridge.log("Clipboard image: verified Google Lens intent requested");
            return true;
        }catch(Throwable failure){XposedBridge.log("Clipboard image: Lens intent unavailable",failure);return false;}
    }

    private ClipboardImageSupport(){}
}
