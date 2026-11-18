package com.gathin.tvhome;

import android.app.Activity;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/**
 * Local addition: the temperature and the air quality under the clock. The temperature has an icon for clear,
 * cloudy or precipitation, the air a dot in the colour of its index. The launcher stays offline: the radio
 * add-on reads both services (addons/radio, WeatherProvider) and this only asks it, when Home comes up and every
 * twenty minutes while it stays up. No add-on or no reading: that part of the line is hidden.
 */
final class Weather {
    private Weather() { }

    static final int CLEAR = 0, CLOUDY = 1, WET = 2;
    private static final String PROVIDER = "content://local.cleanyourtv.radio.weather/";
    private static final long ASK_EVERY_MS = 10 * 60_000;
    private static long asked;
    /** The last answers, kept across a rebuilt Home: "18°C" (null: nothing to show), the service's words for it, the index (-1: none). */
    private static String shown, said = "";
    private static int aqi = -1;

    /**
     * Local addition: the sky as the live weather over the picture needs it (WeatherFx). code is the WMO weather code
     * (0 clear ... 99 thunderstorm with hail), day 1 by day, 0 by night and -1 when unknown, precipitation mm in the
     * last hour, wind km/h. An older add-on sends only the words: they give a rough code ({@link #codeOf}).
     */
    static final class Sky {
        final int code, day;
        final float precipitation, wind;

        Sky(int code, int day, float precipitation, float wind) {
            this.code = code;
            this.day = day;
            this.precipitation = precipitation;
            this.wind = wind;
        }
    }

    /** The last sky read, for every screen in this process (Home and the screensaver); null until one is read. */
    static volatile Sky sky;

    /** A WMO code for the add-on's three words, when it sends no code of its own. */
    static int codeOf(String description) {
        int kind = kind(description == null ? "" : description);
        return kind == CLEAR ? 0 : kind == CLOUDY ? 3 : 61;
    }

    /** Reads the add-on's current row into {@link #sky}; call off the main thread. Missing add-on: sky stays as it was. */
    static void askSky(android.content.Context context) {
        try (Cursor row = context.getContentResolver().query(Uri.parse(PROVIDER + "now"), null, null, null, null)) {
            if (row != null && row.moveToFirst()) sky = skyOf(row);
        } catch (RuntimeException e) {
            // no add-on, or another signing key
        }
    }

    private static Sky skyOf(Cursor row) {
        int code = intColumn(row, "code", -1);
        if (code < 0) code = codeOf(row.getString(1));
        int precipitationAt = row.getColumnIndex("precipitation"), windAt = row.getColumnIndex("wind");
        return new Sky(code, intColumn(row, "day", -1),
                precipitationAt < 0 ? 0 : row.getFloat(precipitationAt), windAt < 0 ? 0 : row.getFloat(windAt));
    }

    private static int intColumn(Cursor row, String name, int otherwise) {
        int at = row.getColumnIndex(name);
        return at < 0 || row.isNull(at) ? otherwise : row.getInt(at);
    }

    /** The service's own words for the weather (Serbian, Latin script: "Slaba kiša", "Pretežno vedro") as one of three. */
    static int kind(String description) {
        String d = description.toLowerCase(Locale.ROOT);
        for (String wet : new String[]{"kiš", "kis", "pljus", "sneg", "snež", "snez", "rosulj", "sipi", "grmlj", "grad", "padavin"}) {
            if (d.contains(wet)) return WET;
        }
        return d.contains("vedro") || d.contains("sunčano") || d.contains("suncano") ? CLEAR : CLOUDY;
    }

    /** Which of the US air quality index's six bands: 0 good, 1 moderate, 2 unhealthy for sensitive groups, 3 unhealthy, 4 very unhealthy, 5 hazardous. */
    static int airBand(int index) {
        return index <= 50 ? 0 : index <= 100 ? 1 : index <= 150 ? 2 : index <= 200 ? 3 : index <= 300 ? 4 : 5;
    }

    /** The colour the index gives that band. */
    static int airColour(int index) {
        return new int[]{0xFF4CAF50, 0xFFFDD835, 0xFFFB8C00, 0xFFE53935, 0xFF8E24AA, 0xFF7E0023}[airBand(index)];
    }
    /** air's start drawable is the dot, a GradientDrawable. */
    static void refresh(Activity activity, TextView line, Icon icon, TextView air) {
        show(line, icon, air);
        long now = System.currentTimeMillis();
        if (now - asked < ASK_EVERY_MS) return;
        asked = now;
        new Thread(() -> {
            String temperature = null, description = "";
            int index = -1;
            try (Cursor row = activity.getContentResolver().query(Uri.parse(PROVIDER + "now"), null, null, null, null)) {
                if (row != null && row.moveToFirst()) {
                    temperature = Math.round(Float.parseFloat(row.getString(0))) + "°C";
                    description = row.getString(1);
                    sky = skyOf(row);
                }
            } catch (RuntimeException e) {
                temperature = null;   // the add-on is missing, signed with another key, or sent something that is not a number
            }
            try (Cursor row = activity.getContentResolver().query(Uri.parse(PROVIDER + "air"), null, null, null, null)) {
                if (row != null && row.moveToFirst()) index = row.getInt(0);
            } catch (RuntimeException e) {
                // as above
            }
            shown = temperature;
            said = description == null ? "" : description;
            aqi = index;
            activity.runOnUiThread(() -> show(line, icon, air));
        }, "weather").start();
    }

    /**
     * Local addition: the sky in words beside the temperature, from the WMO code ("Облачно", "Слаба киша", "Снег").
     * Clear is "Сунчано" by day and "Ведро" by night; day is 1, 0, or -1 for "by the hour".
     */
    static String describe(int code, int day, int hour) {
        boolean isDay = day >= 0 ? day == 1 : hour >= 7 && hour < 18;
        switch (code) {
            case 0: return isDay ? "Сунчано" : "Ведро";
            case 1: return isDay ? "Претежно сунчано" : "Претежно ведро";
            case 2: return "Делимично облачно";
            case 3: return "Облачно";
            case 45: case 48: return "Магла";
            case 51: case 53: case 55: return "Росуља";
            case 56: case 57: return "Ледена росуља";
            case 61: return "Слаба киша";
            case 63: return "Киша";
            case 65: return "Јака киша";
            case 66: case 67: return "Ледена киша";
            case 71: return "Слаб снег";
            case 73: case 77: return "Снег";
            case 75: return "Јак снег";
            case 80: return "Пљусак";
            case 81: return "Пљускови";
            case 82: return "Јаки пљускови";
            case 85: case 86: return "Снежни пљускови";
            case 95: return "Грмљавина";
            case 96: case 99: return "Грмљавина са градом";
            default: return "";
        }
    }

    private static void show(TextView line, Icon icon, TextView air) {
        line.setVisibility(shown == null ? View.GONE : View.VISIBLE);
        Sky now = sky;
        String words = now == null ? "" : describe(now.code, now.day, java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY));
        line.setText(shown == null || words.isEmpty() ? shown : shown + "  ·  " + words);
        line.setContentDescription(shown == null ? null : shown + ", " + (words.isEmpty() ? said : words));
        icon.kind = kind(said);
        icon.invalidateSelf();
        air.setVisibility(aqi < 0 ? View.GONE : View.VISIBLE);
        if (aqi >= 0) {
            // "AQI" says what the number is (an index from 0 to 500, it has no unit); the dot's colour says how bad.
            air.setText(air.getContext().getString(R.string.air_quality, aqi));
            ((GradientDrawable) air.getCompoundDrawablesRelative()[0]).setColor(airColour(aqi));
        }
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
