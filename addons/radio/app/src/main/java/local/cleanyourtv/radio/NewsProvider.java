package local.cleanyourtv.radio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The headlines for the launcher's news ticker, which has no network of its own. ".../now" answers one row per
 * headline {title, source, sport, at}, in the order the ticker shows them, already in Cyrillic (News). The feeds
 * are read on a query at most every ten minutes and the choice is kept, so a failed read keeps the last good one
 * for up to twelve hours. The launcher asks from a background thread.
 */
public class NewsProvider extends ContentProvider {
    private static final long MINUTE = 60_000, HOUR = 60 * MINUTE;
    /** Some of the sites answer a plain Java client with an error page; a TV browser's name gets the feed. */
    private static final String AGENT = "Mozilla/5.0 (Linux; Android 10; Mi TV Stick) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0 Safari/537.36";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        // Local: a provider that throws takes the whole add-on down with it, the playing radio included. Never.
        try {
            return answer(uri);
        } catch (Throwable e) {
            return new MatrixCursor(new String[]{"error"});
        }
    }

    private Cursor answer(Uri uri) {
        if ("/song".equals(uri.getPath())) {   // what the radio plays now, when the station says (RadioService)
            MatrixCursor song = new MatrixCursor(new String[]{"title"});
            String title = RadioService.nowPlaying;
            if (title != null && !title.isEmpty()) song.addRow(new Object[]{title});
            return song;
        }
        SharedPreferences saved = getContext().getSharedPreferences("news", 0);
        long now = System.currentTimeMillis();
        if (now - saved.getLong("at", 0) >= 10 * MINUTE && now - saved.getLong("tried", 0) >= 3 * MINUTE) {
            saved.edit().putLong("tried", now).apply();
            refresh(saved, now);
        }
        MatrixCursor rows = new MatrixCursor(new String[]{"title", "source", "sport", "at"});
        // Local: first Zvezda's game, the exchange rate and a line of Kragujevac history (Ticker), then the headlines.
        for (String[] line : extras(saved, now)) rows.addRow(new Object[]{line[0], line[1], Integer.parseInt(line[2]), now});
        if (now - saved.getLong("at", 0) < 12 * HOUR) {
            try {
                JSONArray items = new JSONArray(saved.getString("items", "[]"));
                for (int i = 0; i < items.length(); i++) {
                    JSONObject o = items.getJSONObject(i);
                    rows.addRow(new Object[]{o.getString("t"), o.getString("s"), o.getInt("p"), o.getLong("a")});
                }
            } catch (Exception e) {
                // a broken copy: no rows until the next good read
            }
        }
        return rows;
    }

    /**
     * The lines before the headlines, each read again only when due: Zvezda's games every minute while one is on or
     * about to start, otherwise every half hour; the exchange rate every three hours. A failed read keeps the last one.
     */
    private static synchronized List<String[]> extras(SharedPreferences saved, long now) {
        List<String[]> out = new ArrayList<>();
        try {
            long gamesDue = saved.getLong("games_due", 0);
            if (now >= gamesDue) {
                String json = get(Ticker.gamesUrl(now));
                List<Ticker.Game> games = Ticker.games(json);
                long next = now + 30 * MINUTE;
                for (Ticker.Game g : games) {
                    boolean soon = !g.played && g.start - now < 20 * MINUTE && now - g.start < 3 * HOUR;
                    if (soon) next = now + MINUTE;
                }
                SharedPreferences.Editor e = saved.edit().putLong("games_due", games.isEmpty() && json.isEmpty() ? now + 5 * MINUTE : next);
                if (!json.isEmpty()) e.putString("games", json);
                e.apply();
            }
            String[] zvezda = Ticker.zvezda(Ticker.games(saved.getString("games", "")), now);
            if (zvezda != null) out.add(new String[]{zvezda[0], "Евролига", zvezda[1]});
        } catch (Exception e) {
            // no game line this time
        }
        try {
            if (now - saved.getLong("rates_at", 0) > 3 * HOUR) {
                double eur = Ticker.middle(get(Ticker.EUR)), usd = Ticker.middle(get(Ticker.USD));
                if (eur > 0 || usd > 0) {
                    saved.edit().putLong("rates_at", now).putFloat("eur", (float) eur).putFloat("usd", (float) usd).apply();
                } else {
                    saved.edit().putLong("rates_at", now - 3 * HOUR + 10 * MINUTE).apply();   // try again in ten minutes
                }
            }
            String rates = Ticker.rates(saved.getFloat("eur", 0), saved.getFloat("usd", 0));
            if (rates != null) out.add(new String[]{rates, "НБС", String.valueOf(Ticker.INFO)});
        } catch (Exception e) {
            // no rate line this time
        }
        out.add(new String[]{Ticker.history(now), "Крагујевац", String.valueOf(Ticker.INFO)});
        return out;
    }

    /** One lock: two asks at once (Home and the screensaver) read the sites once. */
    private static synchronized void refresh(SharedPreferences saved, long now) {
        if (now - saved.getLong("at", 0) < 10 * MINUTE) return;   // the other ask just did it
        List<News.Item> all = new ArrayList<>();
        int read = 0;
        for (News.Feed feed : News.FEEDS) {
            List<News.Item> items = News.parse(get(feed.url), feed);
            if (!items.isEmpty()) read++;
            all.addAll(items);
        }
        if (read == 0) return;   // no network, or every site changed: keep what was shown before
        JSONArray out = new JSONArray();
        try {
            for (News.Item item : News.pick(all, now)) {
                out.put(new JSONObject().put("t", News.cyrillic(item.title)).put("s", item.source)
                        .put("p", item.sport ? 1 : 0).put("a", item.at));
            }
        } catch (Exception e) {
            return;
        }
        if (out.length() == 0) return;
        saved.edit().putString("items", out.toString()).putLong("at", now).apply();
    }

    private static final Pattern ENCODING = Pattern.compile("encoding=[\"']([A-Za-z0-9_\\-]+)[\"']");

    /** The feed as text, in the encoding its XML declaration names (UTF-8 if none); "" when it fails. */
    private static String get(String address) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(address).openConnection();
            http.setConnectTimeout(6000);
            http.setReadTimeout(8000);
            http.setRequestProperty("User-Agent", AGENT);
            http.setRequestProperty("Accept", address.contains("rss") ? "application/rss+xml, application/xml, text/xml, */*"
                    : "application/json, */*");
            if (http.getResponseCode() != 200) return "";
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = http.getInputStream()) {
                byte[] chunk = new byte[16384];
                for (int n; body.size() < 1_500_000 && (n = in.read(chunk)) > 0; ) body.write(chunk, 0, n);
            }
            byte[] bytes = body.toByteArray();
            String head = new String(bytes, 0, Math.min(200, bytes.length), StandardCharsets.ISO_8859_1);
            Charset charset = StandardCharsets.UTF_8;
            Matcher m = ENCODING.matcher(head);
            if (m.find()) {
                try {
                    charset = Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // an encoding Android does not know: UTF-8, which nearly every feed is
                }
            }
            return new String(bytes, charset);
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
