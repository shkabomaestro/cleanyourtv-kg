package local.cleanyourtv.radio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The weather at the Kragujevac station for the launcher's line under the clock, which has no network of its
 * own: one row {temperature, description, at}, or none when nothing recent is known. The source is the hourly
 * observations feed of RHMZ, Serbia's weather service: a measurement at the station, not a forecast.
 * Read on query, at most every twenty minutes; the launcher asks from a background thread.
 */
public class WeatherProvider extends ContentProvider {
    private static final String FEED = "https://www.hidmet.gov.rs/latin/osmotreni/index.rss";
    /** RHMZ's number for the Kragujevac station. Another town: its "ID stanice" in the feed. */
    private static final String STATION = "13278";
    private static final long FRESH_MS = 20 * 60_000, RETRY_MS = 5 * 60_000, STALE_MS = 3 * 60 * 60_000;
    /** "ID stanice: 13278; Temperatura: 18 °C; Pritisak: ...; Opis vremena: Slaba kiša; Šifra ..." */
    private static final Pattern ROW = Pattern.compile(
            "ID stanice: " + STATION + ";\\s*Temperatura:\\s*(-?[0-9]+(?:[.,][0-9]+)?)[^;]*;.*?Opis vremena:\\s*([^;<]*);", Pattern.DOTALL);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SharedPreferences saved = getContext().getSharedPreferences("weather", 0);
        long now = System.currentTimeMillis();
        if (now - saved.getLong("at", 0) > FRESH_MS && now - saved.getLong("tried", 0) > RETRY_MS) {
            saved.edit().putLong("tried", now).apply();
            read(saved, now);
        }
        MatrixCursor row = new MatrixCursor(new String[]{"temperature", "description", "at"});
        long at = saved.getLong("at", 0);
        if (now - at < STALE_MS) row.addRow(new Object[]{saved.getString("temperature", ""), saved.getString("description", ""), at});
        return row;
    }

    /** One request with short timeouts; a failure keeps what was read before, until it turns stale. */
    private static void read(SharedPreferences saved, long now) {
        HttpURLConnection feed = null;
        try {
            feed = (HttpURLConnection) new URL(FEED).openConnection();
            feed.setConnectTimeout(6000);
            feed.setReadTimeout(6000);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = feed.getInputStream()) {
                byte[] chunk = new byte[8192];
                for (int n; body.size() < 200_000 && (n = in.read(chunk)) > 0; ) body.write(chunk, 0, n);
            }
            Matcher station = ROW.matcher(new String(body.toByteArray(), StandardCharsets.UTF_8));
            if (station.find()) {
                saved.edit().putString("temperature", station.group(1).replace(',', '.'))
                        .putString("description", station.group(2).trim()).putLong("at", now).apply();
            }
        } catch (Exception e) {
            // no network just now, or the feed changed shape: the launcher shows nothing once the last reading is stale
        } finally {
            if (feed != null) feed.disconnect();
        }
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
