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
            Class<?> controller=Class.forName("org.chromium.chrome.browser.lens.LensController",false,loader);
            Class<?> params=Class.forName("org.chromium.chrome.browser.lens.LensIntentParams",false,loader);
            Class<?> builder=Class.forName("org.chromium.chrome.browser.lens.LensIntentParams$Builder",false,loader);
            // Source enum CONTEXT_MENU_SEARCH_MENU_ITEM = 0; no native
            // clipboard Lens entry point. Use Chrome's existing image query.
            Object b=builder.getConstructor(int.class,boolean.class).newInstance(0,false);
            builder.getMethod("withImageUri",Uri.class).invoke(b,uri);
            Object p=builder.getMethod("build").invoke(b);
            Object instance=controller.getMethod("getInstance").invoke(null);
            Method launch=controller.getMethod("startLens",Context.class,params);
            launch.invoke(instance,activity,p);
            XposedBridge.log("Clipboard image: Chrome Lens requested");
        }catch(Throwable error){
            XposedBridge.log("Clipboard image: Chrome Lens entry unavailable",error);
            Toast.makeText(activity,"画像検索を開始できませんでした",Toast.LENGTH_SHORT).show();
        }
    }
    private ClipboardImageSupport(){}
}
