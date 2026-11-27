package com.atuy.chromemobilecompat;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Exact paths from Chromium 159: ui/android/java/res/drawable/ic_globe_24dp.xml,
 * ic_search_24dp.xml and components/browser_ui/styles/android/java/res/drawable/
 * ic_visibility_black.xml / ic_visibility_off_black.xml.
 * Copyright 2017, 2025 The Chromium Authors. BSD license in APK assets.
 */
final class ChromiumSuggestionGlyphs {
    static boolean draw(Canvas canvas, int size, String name, Paint paint) {
        switch (name) {
            case "ic_globe_24dp": canvas.scale(size / 960f, size / 960f); ic_globe_24dp(canvas, paint); return true;
            case "ic_search_24dp": canvas.scale(size / 960f, size / 960f); ic_search_24dp(canvas, paint); return true;
            case "ic_visibility_black": canvas.scale(size / 24f, size / 24f); ic_visibility_black(canvas, paint); return true;
            case "ic_visibility_off_black": canvas.scale(size / 24f, size / 24f); ic_visibility_off_black(canvas, paint); return true;
            default: return false;
        }
    }
    private static void ic_globe_24dp(Canvas canvas, Paint paint) {
        Path p = new Path();
        p.moveTo(480f, 880f);
        p.quadTo(397f, 880f, 324f, 848.5f);
        p.quadTo(251f, 817f, 197f, 763f);
        p.quadTo(143f, 709f, 111.5f, 636f);
        p.quadTo(80f, 563f, 80f, 480f);
        p.quadTo(80f, 397f, 111.5f, 324f);
        p.quadTo(143f, 251f, 197f, 197f);
        p.quadTo(251f, 143f, 324f, 111.5f);
        p.quadTo(397f, 80f, 480f, 80f);
        p.quadTo(563f, 80f, 636f, 111.5f);
        p.quadTo(709f, 143f, 763f, 197f);
        p.quadTo(817f, 251f, 848.5f, 324f);
        p.quadTo(880f, 397f, 880f, 480f);
        p.quadTo(880f, 563f, 848.5f, 636f);
        p.quadTo(817f, 709f, 763f, 763f);
        p.quadTo(709f, 817f, 636f, 848.5f);
        p.quadTo(563f, 880f, 480f, 880f);
        p.close();
        p.moveTo(480f, 800f);
        p.quadTo(614f, 800f, 707f, 707f);
        p.quadTo(800f, 614f, 800f, 480f);
        p.quadTo(800f, 473f, 799.5f, 465.5f);
        p.quadTo(799f, 458f, 799f, 453f);
        p.quadTo(794f, 482f, 772f, 501f);
        p.quadTo(750f, 520f, 720f, 520f);
        p.lineTo(640f, 520f);
        p.quadTo(607f, 520f, 583.5f, 496.5f);
        p.quadTo(560f, 473f, 560f, 440f);
        p.lineTo(560f, 400f);
        p.lineTo(400f, 400f);
        p.lineTo(400f, 320f);
        p.quadTo(400f, 287f, 423.5f, 263.5f);
        p.quadTo(447f, 240f, 480f, 240f);
        p.lineTo(520f, 240f);
        p.lineTo(520f, 240f);
        p.quadTo(520f, 217f, 532.5f, 199.5f);
        p.quadTo(545f, 182f, 563f, 171f);
        p.quadTo(543f, 166f, 522.5f, 163f);
        p.quadTo(502f, 160f, 480f, 160f);
        p.quadTo(346f, 160f, 253f, 253f);
        p.quadTo(160f, 346f, 160f, 480f);
        p.quadTo(160f, 480f, 160f, 480f);
        p.quadTo(160f, 480f, 160f, 480f);
        p.lineTo(360f, 480f);
        p.quadTo(426f, 480f, 473f, 527f);
        p.quadTo(520f, 574f, 520f, 640f);
        p.lineTo(520f, 680f);
        p.lineTo(400f, 680f);
        p.lineTo(400f, 790f);
        p.quadTo(420f, 795f, 439.5f, 797.5f);
        p.quadTo(459f, 800f, 480f, 800f);
        p.close();
        canvas.drawPath(p, paint);
    }

    private static void ic_search_24dp(Canvas canvas, Paint paint) {
        Path p = new Path();
        p.moveTo(784f, 840f);
        p.lineTo(532f, 588f);
        p.quadTo(502f, 612f, 463f, 626f);
        p.quadTo(424f, 640f, 380f, 640f);
        p.quadTo(271f, 640f, 195.5f, 564.5f);
        p.quadTo(120f, 489f, 120f, 380f);
        p.quadTo(120f, 271f, 195.5f, 195.5f);
        p.quadTo(271f, 120f, 380f, 120f);
        p.quadTo(489f, 120f, 564.5f, 195.5f);
        p.quadTo(640f, 271f, 640f, 380f);
        p.quadTo(640f, 424f, 626f, 463f);
        p.quadTo(612f, 502f, 588f, 532f);
        p.lineTo(840f, 784f);
        p.lineTo(784f, 840f);
        p.close();
        p.moveTo(380f, 560f);
        p.quadTo(455f, 560f, 507.5f, 507.5f);
        p.quadTo(560f, 455f, 560f, 380f);
        p.quadTo(560f, 305f, 507.5f, 252.5f);
        p.quadTo(455f, 200f, 380f, 200f);
        p.quadTo(305f, 200f, 252.5f, 252.5f);
        p.quadTo(200f, 305f, 200f, 380f);
        p.quadTo(200f, 455f, 252.5f, 507.5f);
        p.quadTo(305f, 560f, 380f, 560f);
        p.close();
        canvas.drawPath(p, paint);
    }

    private static void ic_visibility_black(Canvas canvas, Paint paint) {
        Path p = new Path();
        p.moveTo(12f, 4.5f);
        p.cubicTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f);
        p.cubicTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f);
        p.cubicTo(17f, 19.5f, 21.27f, 16.39f, 23f, 12f);
        p.cubicTo(21.27f, 7.61f, 17f, 4.5f, 12f, 4.5f);
        p.close();
        p.moveTo(12f, 17f);
        p.cubicTo(9.24f, 17f, 7f, 14.76f, 7f, 12f);
        p.cubicTo(7f, 9.24f, 9.24f, 7f, 12f, 7f);
        p.cubicTo(14.76f, 7f, 17f, 9.24f, 17f, 12f);
        p.cubicTo(17f, 14.76f, 14.76f, 17f, 12f, 17f);
        p.close();
        p.moveTo(12f, 9f);
        p.cubicTo(10.34f, 9f, 9f, 10.34f, 9f, 12f);
        p.cubicTo(9f, 13.66f, 10.34f, 15f, 12f, 15f);
        p.cubicTo(13.66f, 15f, 15f, 13.66f, 15f, 12f);
        p.cubicTo(15f, 10.34f, 13.66f, 9f, 12f, 9f);
        p.close();
        canvas.drawPath(p, paint);
    }

    private static void ic_visibility_off_black(Canvas canvas, Paint paint) {
        Path p = new Path();
        p.moveTo(12f, 7f);
        p.cubicTo(14.76f, 7f, 17f, 9.24f, 17f, 12f);
        p.cubicTo(17f, 12.65f, 16.87f, 13.26f, 16.64f, 13.83f);
        p.lineTo(19.56f, 16.75f);
        p.cubicTo(21.07f, 15.49f, 22.26f, 13.86f, 22.99f, 12f);
        p.cubicTo(21.26f, 7.61f, 16.99f, 4.5f, 11.99f, 4.5f);
        p.cubicTo(10.59f, 4.5f, 9.25f, 4.75f, 8.01f, 5.2f);
        p.lineTo(10.17f, 7.36f);
        p.cubicTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f);
        p.close();
        p.moveTo(2f, 4.27f);
        p.lineTo(4.28f, 6.55f);
        p.lineTo(4.74f, 7.01f);
        p.cubicTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f);
        p.cubicTo(2.73f, 16.39f, 7f, 19.5f, 12f, 19.5f);
        p.cubicTo(13.55f, 19.5f, 15.03f, 19.2f, 16.38f, 18.66f);
        p.lineTo(16.8f, 19.08f);
        p.lineTo(19.73f, 22f);
        p.lineTo(21f, 20.73f);
        p.lineTo(3.27f, 3f);
        p.lineTo(2f, 4.27f);
        p.close();
        p.moveTo(7.53f, 9.8f);
        p.lineTo(9.08f, 11.35f);
        p.cubicTo(9.03f, 11.56f, 9f, 11.78f, 9f, 12f);
        p.cubicTo(9f, 13.66f, 10.34f, 15f, 12f, 15f);
        p.cubicTo(12.22f, 15f, 12.44f, 14.97f, 12.65f, 14.92f);
        p.lineTo(14.2f, 16.47f);
        p.cubicTo(13.53f, 16.8f, 12.79f, 17f, 12f, 17f);
        p.cubicTo(9.24f, 17f, 7f, 14.76f, 7f, 12f);
        p.cubicTo(7f, 11.21f, 7.2f, 10.47f, 7.53f, 9.8f);
        p.close();
        p.moveTo(11.84f, 9.02f);
        p.lineTo(14.99f, 12.17f);
        p.lineTo(15.01f, 12.01f);
        p.cubicTo(15.01f, 10.35f, 13.67f, 9.01f, 12.01f, 9.01f);
        p.lineTo(11.84f, 9.02f);
        p.close();
        canvas.drawPath(p, paint);
    }

    private ChromiumSuggestionGlyphs() {}
}
