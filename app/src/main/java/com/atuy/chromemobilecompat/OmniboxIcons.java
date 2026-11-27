package com.atuy.chromemobilecompat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import com.atuy.chromemobilecompat.compat.XposedBridge;

import java.io.InputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.HashSet;
import java.util.Set;

/**
 * One icon policy for all three page-card rendering paths.
 * Source: Chromium EditUrlSuggestionProcessor / OmniboxDrawableState.
 * Copy/edit fallback geometry from Chromium's BSD-licensed drawable resources
 * (components/embedder_support/android/java/res/drawable/ic_content_copy.xml
 * and components/browser_ui/styles/android/java/res/drawable/ic_edit_24dp.xml).
 * Share fallback is the conventional three-node glyph, not the Android
 * framework's unrelated menu icon.
 */
final class OmniboxIcons {
    private static final Set<String> REPORTED = new HashSet<>();
    private static volatile String moduleApk;
    private static Bitmap shareLtr, shareRtl;

    static void initialize(ApplicationInfo info) {
        if (info != null) moduleApk = info.sourceDir;
    }

    static void action(ImageView view, String name, int tint) {
        Context context = view.getContext();
        int size = Math.max(1, NativeOmniboxPageCard.dimension(
                context, "omnibox_suggestion_icon_small_size", 20));
        int max = Math.round(32 * context.getResources().getDisplayMetrics().density);
        if (size > max) size = Math.round(20 * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Canvas canvas = new Canvas(bitmap);
        Drawable drawable = installed(context, name);
        String source;
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(Color.WHITE);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            source = "Chrome-resource";
        } else {
            boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            Bitmap share = "ic_share_white_24dp".equals(name) ? bundledShare(rtl) : null;
            if (valid(share)) {
                Paint filter = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(share, null, new android.graphics.Rect(0, 0, size, size), filter);
                source = "Chromium-png";
            } else {
                drawFallback(canvas, size, name, rtl);
                source = "ic_share_white_24dp".equals(name) ? "emergency-geometry" : "Chromium-vector";
            }
        }
        view.setImageBitmap(bitmap);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (REPORTED.add(name)) XposedBridge.log("Omnibox icon " + name
                + " source=" + source
                + " sizePx=" + size);
    }

    private static Bitmap bundledShare(boolean rtl) {
        Bitmap cached = rtl ? shareRtl : shareLtr;
        if (valid(cached)) return cached;
        String apk = moduleApk;
        if (apk == null) return null;
        try (ZipFile zip = new ZipFile(apk)) {
            String name = rtl ? "assets/chromium_share_rtl.png" : "assets/chromium_share_ltr.png";
            ZipEntry asset = zip.getEntry(name);
            if (asset == null) return null;
            try (InputStream stream = zip.getInputStream(asset)) {
                Bitmap bitmap = BitmapFactory.decodeStream(stream);
                if (rtl) shareRtl = bitmap; else shareLtr = bitmap;
                return bitmap;
            }
        } catch (Throwable t) {
            XposedBridge.log("Omnibox share asset unavailable", t);
            return null;
        }
    }

    private static Drawable installed(Context context, String name) {
        Resources res = context.getResources();
        int resource = res.getIdentifier(name, "drawable", "com.android.chrome");
        if (resource == 0) resource = res.getIdentifier(name, "drawable", context.getPackageName());
        if (resource == 0) return null;
        try { return context.getDrawable(resource); }
        catch (Resources.NotFoundException ignored) { return null; }
    }

    private static void drawFallback(Canvas canvas, int size, String name, boolean rtl) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        Path path = new Path();
        if ("ic_content_copy".equals(name)) {
            canvas.scale(size / 24f, size / 24f);
            path.moveTo(18,21); path.lineTo(4,21); path.lineTo(4,7);
            path.lineTo(2,7); path.lineTo(2,21); path.cubicTo(2,22.1f,2.9f,23,4,23);
            path.lineTo(18,23); path.close();
            path.moveTo(21,17); path.lineTo(21,3); path.cubicTo(21,1.9f,20.1f,1,19,1);
            path.lineTo(8,1); path.cubicTo(6.9f,1,6,1.9f,6,3); path.lineTo(6,17);
            path.cubicTo(6,18.1f,6.9f,19,8,19); path.lineTo(19,19);
            path.cubicTo(20.1f,19,21,18.1f,21,17); path.close();
            path.moveTo(19,17); path.lineTo(8,17); path.lineTo(8,3); path.lineTo(19,3); path.close();
            path.setFillType(Path.FillType.EVEN_ODD);
            canvas.drawPath(path, paint);
        } else if ("ic_edit_24dp".equals(name)) {
            canvas.scale(size / 960f, size / 960f);
            path.moveTo(200,760); path.lineTo(257,760); path.lineTo(648,369);
            path.lineTo(591,312); path.lineTo(200,703); path.close();
            path.moveTo(120,840); path.lineTo(120,670); path.lineTo(648,143);
            path.quadTo(660,132,674.5f,126); path.quadTo(689,120,705,120);
            path.quadTo(721,120,736,126); path.quadTo(751,132,762,144);
            path.lineTo(817,200); path.quadTo(829,211,834.5f,226);
            path.quadTo(840,241,840,256); path.quadTo(840,272,834.5f,286.5f);
            path.quadTo(829,301,817,313); path.lineTo(290,840); path.close();
            path.setFillType(Path.FillType.EVEN_ODD);
            canvas.drawPath(path, paint);
        } else {
            canvas.scale(size / 24f, size / 24f);
            if (rtl) { canvas.translate(24,0); canvas.scale(-1,1); }
            paint.setStrokeWidth(2);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(6,12,18,5,paint);
            canvas.drawLine(6,12,18,19,paint);
            canvas.drawCircle(6,12,3,paint);
            canvas.drawCircle(18,5,3,paint);
            canvas.drawCircle(18,19,3,paint);
        }
    }

    static boolean valid(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }

    static int pageIconSize(Context context) {
        // OmniboxResourceProvider.getEdgeSize(SMALL) on mobile Chrome.
        return Math.max(1, NativeOmniboxPageCard.dimension(
                context, "omnibox_suggestion_24dp_icon_size", 24));
    }

    static void pageDecoration(ImageView view, Bitmap bitmap, int tint) {
        int edge = pageIconSize(view.getContext());
        android.view.ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.width = edge;
            params.height = edge;
            view.setLayoutParams(params);
        }
        view.setPadding(0, 0, 0, 0);
        view.setAdjustViewBounds(true);
        view.setMaxWidth(edge);
        view.setMaxHeight(edge);
        view.setMinimumWidth(edge);
        view.setMinimumHeight(edge);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setVisibility(View.VISIBLE);
        view.setAlpha(1f);
        view.setImageAlpha(255);
        if (valid(bitmap)) {
            favicon(view, bitmap);
            return;
        }
        suggestionGlyph(view, "ic_globe_24dp", tint);
        view.setClipToOutline(false);
        view.requestLayout();
    }

    static void suggestionGlyph(ImageView view, String name, int tint) {
        int edge = pageIconSize(view.getContext());
        Bitmap generic = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
        generic.setDensity(view.getResources().getDisplayMetrics().densityDpi);
        Canvas canvas = new Canvas(generic);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        // Use the source artwork even if release resource names were collapsed
        // or the installed desktop variant exposes a different globe glyph.
        if (!ChromiumSuggestionGlyphs.draw(canvas, edge, name, paint)) return;
        view.setImageBitmap(generic);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setVisibility(View.VISIBLE);
        if (REPORTED.add(name)) XposedBridge.log("Omnibox icon " + name
                + " source=Chromium-vector sizePx=" + edge);
    }

    static void favicon(ImageView view, Bitmap bitmap) {
        if (!valid(bitmap)) return;
        // Native OmniboxDrawableState.forFavIcon never applies text tint.
        view.setImageTintList(null);
        view.clearColorFilter();
        view.setImageAlpha(255);
        view.setAlpha(1f);
        view.setImageBitmap(bitmap);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setVisibility(View.VISIBLE);
        int size = pageIconSize(view.getContext());
        view.setMinimumWidth(size);
        view.setMinimumHeight(size);
        final int radius = NativeOmniboxPageCard.dimension(
                view.getContext(), "omnibox_small_icon_rounding_radius", 2);
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
        view.requestLayout();
        view.invalidate();
    }

    private OmniboxIcons() {}
}
