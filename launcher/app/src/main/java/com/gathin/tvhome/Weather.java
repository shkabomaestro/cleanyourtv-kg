package com.gathin.tvhome;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/**
 * Local addition: the temperature under the clock, with an icon for clear, cloudy or precipitation. The launcher
 * stays offline: the radio add-on reads the weather service (addons/radio, WeatherProvider) and this only asks it,
 * when Home comes up and the last answer is older than ten minutes. No add-on or no reading: the line is hidden.
 */
final class Weather {
    private Weather() { }

    static final int CLEAR = 0, CLOUDY = 1, WET = 2;
    private static final String NOW = "content://local.cleanyourtv.radio.weather/now";
    private static final long ASK_EVERY_MS = 10 * 60_000;
    private static long asked;
    /** The last answer, kept across a rebuilt Home: "18°C" (null: nothing to show) and the service's words for it. */
    private static String shown, said = "";

    /** The service's own words for the weather (Serbian, Latin script: "Slaba kiša", "Pretežno vedro") as one of three. */
    static int kind(String description) {
        String d = description.toLowerCase(Locale.ROOT);
        for (String wet : new String[]{"kiš", "kis", "pljus", "sneg", "snež", "snez", "rosulj", "sipi", "grmlj", "grad", "padavin"}) {
            if (d.contains(wet)) return WET;
        }
        return d.contains("vedro") || d.contains("sunčano") || d.contains("suncano") ? CLEAR : CLOUDY;
    }

    static void refresh(Activity activity, TextView line, Icon icon) {
        show(line, icon);
        long now = System.currentTimeMillis();
        if (now - asked < ASK_EVERY_MS) return;
        asked = now;
        new Thread(() -> {
            String temperature = null, description = "";
            try (Cursor row = activity.getContentResolver().query(Uri.parse(NOW), null, null, null, null)) {
                if (row != null && row.moveToFirst()) {
                    temperature = Math.round(Float.parseFloat(row.getString(0))) + "°C";
                    description = row.getString(1);
                }
            } catch (RuntimeException e) {
                temperature = null;   // the add-on is missing, signed with another key, or sent something that is not a number
            }
            shown = temperature;
            said = description == null ? "" : description;
            activity.runOnUiThread(() -> show(line, icon));
        }, "weather").start();
    }

    private static void show(TextView line, Icon icon) {
        line.setVisibility(shown == null ? View.GONE : View.VISIBLE);
        line.setText(shown);
        line.setContentDescription(shown == null ? null : shown + ", " + said);
        icon.kind = kind(said);
        icon.invalidateSelf();
    }

    /** A sun, a cloud, or a cloud with drops, in white; coordinates are dp on a 26x22 box. */
    static final class Icon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        int kind = CLOUDY;

        Icon(float dp) {
            this.dp = dp;
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setShadowLayer(3 * dp, 0, dp, 0x99000000);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(dp, dp);
            paint.setStyle(Paint.Style.FILL);
            if (kind == CLEAR) {
                canvas.drawCircle(13, 11, 4.6f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.7f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    canvas.drawLine(13 + 7 * cos, 11 + 7 * sin, 13 + 9.6f * cos, 11 + 9.6f * sin, paint);
                }
            } else {
                float top = kind == WET ? 0 : 2.5f;   // the rain cloud sits higher, to leave room for the drops
                canvas.drawCircle(9, top + 9, 4.2f, paint);
                canvas.drawCircle(14.5f, top + 6.6f, 5.6f, paint);
                canvas.drawCircle(19.4f, top + 10, 3.6f, paint);
                canvas.drawRoundRect(new RectF(5, top + 8.6f, 23, top + 14), 2.7f, 2.7f, paint);
                if (kind == WET) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.7f);
                    for (float x : new float[]{9.5f, 14.5f, 19.5f}) canvas.drawLine(x, 16.8f, x - 1.6f, 20.6f, paint);
                }
            }
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return Math.round(26 * dp); }
        @Override public int getIntrinsicHeight() { return Math.round(22 * dp); }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
