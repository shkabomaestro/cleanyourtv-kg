package local.cleanyourtv.radio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kragujevac's weather and air for the launcher's line under the clock, which has no network of its own.
 * ".../now" answers one row {temperature, description, at}: the hourly measurement at the Kragujevac station of
 * RHMZ, Serbia's weather service, not a forecast. ".../air" answers one row {aqi, at}: the US air quality index
 * IQAir gives for the town. Either answers no row when nothing recent is known. Read on query, the weather at
 * most every twenty minutes and the air every hour; the launcher asks from a background thread.
 */
public class WeatherProvider extends ContentProvider {
    private static final long MINUTE = 60_000, HOUR = 60 * MINUTE;
    private static final String FEED = "https://www.hidmet.gov.rs/latin/osmotreni/index.rss";
    /** RHMZ's number for the Kragujevac station. Another town: its "ID stanice" in the feed. */
    private static final String STATION = "13278";
    /** "ID stanice: 13278; Temperatura: 18 °C; Pritisak: ...; Opis vremena: Slaba kiša; Šifra ..." */
    private static final Pattern ROW = Pattern.compile(
            "ID stanice: " + STATION + ";\\s*Temperatura:\\s*(-?[0-9]+(?:[.,][0-9]+)?)[^;]*;.*?Opis vremena:\\s*([^;<]*);", Pattern.DOTALL);
    /** IQAir's API, the place as its site names it (iqair.com/serbia/central-serbia/kragujevac); the key follows. */
    private static final String AIR = "https://api.airvisual.com/v2/city?city=Kragujevac&state=Central%20Serbia&country=Serbia&key=";
    /** The owner's own IQAir key, one line, pushed over adb into this app's files folder. Without it: no air row. */
    private static final String KEY_FILE = "iqair-key.txt";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return "/air".equals(uri.getPath()) ? air() : weather();
    }

    private Cursor weather() {
        SharedPreferences saved = getContext().getSharedPreferences("weather", 0);
        long now = System.currentTimeMillis();
        if (due(saved, now, 20 * MINUTE, 5 * MINUTE)) {
            Matcher station = ROW.matcher(get(FEED));
            if (station.find()) {
                saved.edit().putString("temperature", station.group(1).replace(',', '.'))
                        .putString("description", station.group(2).trim()).putLong("at", now).apply();
            }
        }
        MatrixCursor row = new MatrixCursor(new String[]{"temperature", "description", "at"});
        long at = saved.getLong("at", 0);
        if (now - at < 3 * HOUR) row.addRow(new Object[]{saved.getString("temperature", ""), saved.getString("description", ""), at});
        return row;
    }

    private Cursor air() {
        SharedPreferences saved = getContext().getSharedPreferences("air", 0);
        long now = System.currentTimeMillis();
        String key = key();
        if (!key.isEmpty() && due(saved, now, HOUR, 15 * MINUTE)) {
            try {
                JSONObject answer = new JSONObject(get(AIR + Uri.encode(key)));
                int aqi = answer.getJSONObject("data").getJSONObject("current").getJSONObject("pollution").getInt("aqius");
                saved.edit().putInt("aqi", aqi).putLong("at", now).apply();
            } catch (Exception e) {
                // no answer, a refused key ({"status":"fail"}), or another shape: nothing new to show
            }
        }
        MatrixCursor row = new MatrixCursor(new String[]{"aqi", "at"});
        long at = saved.getLong("at", 0);
        if (now - at < 6 * HOUR) row.addRow(new Object[]{saved.getInt("aqi", 0), at});
        return row;
    }

    /** True when the last reading is older than fresh and the last try older than retry; notes the try. */
    private static boolean due(SharedPreferences saved, long now, long fresh, long retry) {
        if (now - saved.getLong("at", 0) < fresh || now - saved.getLong("tried", 0) < retry) return false;
        saved.edit().putLong("tried", now).apply();
        return true;
    }

    private String key() {
        try {
            return new String(Files.readAllBytes(new File(getContext().getExternalFilesDir(null), KEY_FILE).toPath()),
                    StandardCharsets.UTF_8).replace("﻿", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** The body of one GET with short timeouts, or "" when it fails; a failure keeps what was read before. */
    private static String get(String address) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(address).openConnection();
            http.setConnectTimeout(6000);
            http.setReadTimeout(6000);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] chunk = new byte[8192];
                for (int n; body.size() < 200_000 && (n = in.read(chunk)) > 0; ) body.write(chunk, 0, n);
            }
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            if (http != null) http.disconnect();
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
