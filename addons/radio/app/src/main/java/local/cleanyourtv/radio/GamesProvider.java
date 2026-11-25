package local.cleanyourtv.radio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Zvezda's next football and basketball games for Home's game panel under the clock, through the launcher's own
 * game_day contract (docs/modules.md; the theme names this package in "game_day"). One turn per sport with a game
 * within two weeks, from Sofascore (Zvezda), the basketball one from the EuroLeague feed the ticker reads when
 * Sofascore gives nothing. Exported without a permission, as the contract asks: it holds the game lines and nothing
 * else.
 *
 * Read again every hour; every two minutes from half an hour before a kick-off until the game is over, and then
 * Home is told (notifyChange) so the score on it moves too. A failed read keeps the last answer and tries again in ten
 * minutes.
 */
public class GamesProvider extends ContentProvider {
    private static final long MINUTE = 60_000, HOUR = 60 * MINUTE;
    private static final String[] COLUMNS = {"top_line", "word", "start_ms", "end_ms", "colour", "calendar", "turn_seconds"};
    /** Zvezda's red, the short bar beside each line. */
    private static final int RED = 0xFFE30613;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            return turns(uri);
        } catch (Throwable e) {   // never take the playing radio down with a bad answer
            return new MatrixCursor(COLUMNS);
        }
    }

    private Cursor turns(Uri uri) {
        long now = System.currentTimeMillis();
        SharedPreferences saved = getContext().getSharedPreferences("zvezda", 0);
        SharedPreferences news = getContext().getSharedPreferences("news", 0);
        MatrixCursor rows = new MatrixCursor(COLUMNS);
        boolean busy = false;
        Object[][] found = new Object[2][];
        int k = 0;
        for (boolean football : new boolean[] {true, false}) {
            String sport = football ? "football" : "basketball";
            Zvezda.Game game = read(saved, sport, football, now);
            if (game == null && !football) game = euroleague(news.getString("games", ""), now);
            String[] line = Zvezda.line(game, football, now);
            if (line == null) continue;
            long end = game.start + Zvezda.PLAYING_MS;
            if (Integer.parseInt(line[1]) == Zvezda.LIVE && end <= now) end = now + 15 * MINUTE;   // running late
            busy |= game.start - now < 30 * MINUTE;
            found[k++] = new Object[] {line[0], football ? "⚽" : "🏀", game.start, end, RED, "Sofascore", 20};
        }
        if (k == 2 && (long) found[1][2] < (long) found[0][2]) {   // sorted by start, as the contract asks
            Object[] first = found[0];
            found[0] = found[1];
            found[1] = first;
        }
        for (int i = 0; i < k; i++) rows.addRow(found[i]);
        MAIN.removeCallbacksAndMessages(null);
        if (busy) {   // a game on or about to start: Home asks again in two minutes, and gets the new score
            MAIN.postDelayed(() -> getContext().getContentResolver().notifyChange(uri, null), 2 * MINUTE);
        }
        return rows;
    }

    /** The saved answer for one sport, read again from Sofascore when it is due. */
    private static synchronized Zvezda.Game read(SharedPreferences saved, String sport, boolean football, long now) {
        if (now >= saved.getLong(sport + "_due", 0)) {
            String json = NewsProvider.get(Zvezda.url(football ? Zvezda.FOOTBALL_TEAM : Zvezda.BASKETBALL_TEAM));
            Zvezda.Game fresh = json.isEmpty() ? null : Zvezda.next(json, now);
            long due = now + (json.isEmpty() ? 10 * MINUTE : HOUR);
            if (fresh != null && fresh.start - now < 30 * MINUTE) due = now + 2 * MINUTE;
            SharedPreferences.Editor e = saved.edit().putLong(sport + "_due", due);
            if (!json.isEmpty()) e.putString(sport + "_json", json);
            e.apply();
        }
        return Zvezda.next(saved.getString(sport + "_json", ""), now);
    }

    /** The next EuroLeague game from the ticker's copy of the feed. */
    private static Zvezda.Game euroleague(String json, long now) {
        Ticker.Game next = null;
        for (Ticker.Game g : Ticker.games(json)) {
            if (!g.played && g.start + Zvezda.PLAYING_MS > now && (next == null || g.start < next.start)) next = g;
        }
        return next == null ? null : new Zvezda.Game(next.start, next.home, next.away, "Euroleague", false, -1, -1);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
