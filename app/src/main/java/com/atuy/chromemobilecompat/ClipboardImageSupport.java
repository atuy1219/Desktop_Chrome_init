package com.atuy.chromemobilecompat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.widget.Toast;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import android.content.Intent;
import android.content.ClipData;
import android.content.pm.ProviderInfo;
import android.content.res.XmlResourceParser;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Modifier;
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
    private static final String GSA = "com.google.android.googlequicksearchbox";
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long SHARE_LIFETIME = 10 * 60 * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy;

    static void search(Activity activity,Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme()) || busy) {
            XposedBridge.log("Clipboard image: request rejected (invalid URI or pending)");
            return;
        }
        busy = true;
        XposedBridge.log("Clipboard image: explicit search selected");
        // A clipboard provider may grant Chrome read access but not allow
        // delegation to AGSA. Copy only the selected image into Chrome's
        // own existing image-sharing directory, off the UI thread.
        new Thread(() -> {
            File file = null;
            Uri share = null;
            try {
                file = prepareImage(activity, uri);
                share = chromeImageUri(activity, file);
                XposedBridge.log("Clipboard image: prepared Chrome-owned share URI");
            } catch (Throwable failure) {
                XposedBridge.log("Clipboard image: preparation failed", failure);
                if(file!=null) file.delete();
                file=null;
            }
            final File temporary=file;
            final Uri prepared=share;
            MAIN.post(() -> {
                busy=false;
                if(activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()){
                    if(temporary!=null)temporary.delete();
                    XposedBridge.log("Clipboard image: activity no longer foreground");
                    return;
                }
                Uri selected=prepared!=null?prepared:uri;
                if(launchLensIntent(activity,selected)){
                    if(temporary!=null) MAIN.postDelayed(()->{
                        try{activity.revokeUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                        catch(Throwable ignored){}
                        temporary.delete();
                    },SHARE_LIFETIME);
                } else {
                    if(temporary!=null)temporary.delete();
                    Toast.makeText(activity,"画像検索を開始できませんでした。Googleアプリを確認してください。",Toast.LENGTH_LONG).show();
                }
            });
        },"ChromeClipboardImageShare").start();
    }

    private static File prepareImage(Context ctx, Uri source) throws Exception {
        // Only Chrome's configured image-sharing root, not arbitrary files
        // or other provider roots. Verify its actual XML metadata each time.
        ProviderInfo provider=ctx.getPackageManager().resolveContentProvider(
                ctx.getPackageName()+".FileProvider",
                android.content.pm.PackageManager.GET_META_DATA);
        if(provider==null || !ctx.getPackageName().equals(provider.packageName)
                || provider.exported || !provider.grantUriPermissions)
            throw new IllegalStateException("Chrome image provider unavailable");
        File directory=null;
        try(XmlResourceParser xml=provider.loadXmlMetaData(ctx.getPackageManager(),
                "android.support.FILE_PROVIDER_PATHS")){
            if(xml==null)throw new IllegalStateException("provider paths unavailable");
            for(int event=xml.getEventType();event!=XmlResourceParser.END_DOCUMENT;event=xml.next()){
                if(event!=XmlResourceParser.START_TAG || !"images".equals(xml.getAttributeValue(null,"name")))continue;
                File root="files-path".equals(xml.getName())?ctx.getFilesDir():
                        "cache-path".equals(xml.getName())?ctx.getCacheDir():null;
                String path=xml.getAttributeValue(null,"path");
                if(root==null || path==null || path.isEmpty())continue;
                File candidate=new File(root,path).getCanonicalFile();
                if(candidate.getPath().startsWith(root.getCanonicalPath()+File.separator)){
                    if(directory!=null && !directory.equals(candidate))throw new IllegalStateException("ambiguous image root");
                    directory=candidate;
                }
            }
        }
        if(directory==null || (!directory.isDirectory() && !directory.mkdirs()))
            throw new IllegalStateException("image-sharing root unavailable");
        File[] old=directory.listFiles((d,n)->n.startsWith("cmc_clipboard_"));
        if(old!=null)for(File f:old)if(System.currentTimeMillis()-f.lastModified()>SHARE_LIFETIME)f.delete();
        String mime=ctx.getContentResolver().getType(source);
        String ext=mime==null?null:android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if(ext==null || !ext.matches("[a-zA-Z0-9]{1,10}"))ext="img";
        File output=File.createTempFile("cmc_clipboard_","."+ext,directory);
        boolean complete=false;
        try {
            try(InputStream in=ctx.getContentResolver().openInputStream(source);
                    FileOutputStream out=new FileOutputStream(output)){
                if(in==null)throw new java.io.IOException("clipboard stream unavailable");
                byte[] buf=new byte[65536];
                long total=0;
                for(int n;(n=in.read(buf))!=-1;){
                    if(n==0)continue;
                    total+=n;
                    if(total>MAX_BYTES)throw new java.io.IOException("image exceeds share limit");
                    out.write(buf,0,n);
                }
                if(total==0)throw new java.io.IOException("empty clipboard image");
            }
            BitmapFactory.Options bounds=new BitmapFactory.Options();
            bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(output.getAbsolutePath(),bounds);
            if(bounds.outWidth<=0 || bounds.outHeight<=0)
                throw new java.io.IOException("not a decodable image");
            complete=true;
            return output;
        } finally {if(!complete)output.delete();}
    }

    private static Uri chromeImageUri(Context ctx,File file) throws Exception {
        // AndroidX FileProvider.getUriForFile(Context,String,File) is R8
        // renamed in this APK. Resolve the inherited static signature, not
        // its current method or superclass name.
        String authority=ctx.getPackageName()+".FileProvider";
        ProviderInfo info=ctx.getPackageManager().resolveContentProvider(authority,0);
        if(info==null || !ctx.getPackageName().equals(info.packageName))throw new IllegalStateException("provider unavailable");
        Class<?> cls=Class.forName(info.name,false,ctx.getClassLoader());
        Method match=null;
        for(Class<?> c=cls;c!=null;c=c.getSuperclass()){
            for(Method m:c.getDeclaredMethods()){
                if(!Modifier.isStatic(m.getModifiers()) || m.getReturnType()!=Uri.class
                        || !java.util.Arrays.equals(m.getParameterTypes(),new Class<?>[]{Context.class,String.class,File.class}))continue;
                if(match!=null)throw new IllegalStateException("ambiguous FileProvider method");
                match=m;
            }
        }
        if(match==null)throw new NoSuchMethodException("FileProvider URI factory");
        match.setAccessible(true);
        Uri result=(Uri)match.invoke(null,ctx,authority,file);
        if(result==null || !"content".equals(result.getScheme()) || !authority.equals(result.getAuthority()))
            throw new IllegalStateException("invalid image URI");
        return result;
    }

    private static boolean launchLensIntent(Activity activity,Uri uri){
        if(!ExtraUiCompatibility.lensIntentProtocol){
            XposedBridge.log("Clipboard image: Lens protocol not resolved");
            return false;
        }
        boolean granted=false;
        try{
            // The installed Chromium intent builder explicitly grants this
            // URI to AGSA, in addition to the flag on the intent. Query
            // parameters alone do not convey Android URI permissions.
            activity.grantUriPermission(GSA,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            granted=true;
            Uri data=Uri.parse("googleapp://lens").buildUpon()
                    .appendQueryParameter("LensBitmapUriKey",uri.toString())
                    .appendQueryParameter("AccountNameUriKey","")
                    .appendQueryParameter("IncognitoUriKey","false")
                    .appendQueryParameter("ActivityLaunchTimestampNanos",
                            Long.toString(android.os.SystemClock.elapsedRealtimeNanos()))
                    .build();
            Intent intent=new Intent(Intent.ACTION_VIEW,data)
                    .setPackage(GSA)
                    .putExtra("force_unlock_orientation",false)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Image",uri));
            activity.startActivity(intent);
            if(!uri.getAuthority().equals(activity.getPackageName()+".FileProvider"))
                MAIN.postDelayed(()->{
                    try{activity.revokeUriPermission(GSA,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                    catch(Throwable ignored){}
                },SHARE_LIFETIME);
            XposedBridge.log("Clipboard image: Lens activity launch requested (URI grant applied)");
            return true;
        }catch(Throwable failure){
            if(granted)try{activity.revokeUriPermission(GSA,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                catch(Throwable ignored){}
            XposedBridge.log("Clipboard image: Lens intent failed ("+failure.getClass().getSimpleName()+")");
            return false;
        }
    }
    private ClipboardImageSupport(){}
}
