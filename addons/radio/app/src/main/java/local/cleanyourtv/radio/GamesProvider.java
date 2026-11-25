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
 * within two weeks: football from FotMob's calendar, basketball from the EuroLeague feed (Zvezda). Exported without a permission, as the contract asks: it holds the game lines and nothing
 * else.
 *
 * While a game is about to start or on, Home is told every two minutes (notifyChange) so the line and the score on
 * it move too. A failed read keeps the last answer and tries again in ten minutes.
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
        MatrixCursor rows = new MatrixCursor(COLUMNS);
        boolean busy = false;
        Object[][] found = new Object[2][];
        int k = 0;
        for (boolean football : new boolean[] {true, false}) {
            String sport = football ? "football" : "basketball";
            Zvezda.Game game = read(saved, sport, football, now);
            String[] line = Zvezda.line(game, football, now);
            if (line == null) continue;
            long end = game.start + Zvezda.PLAYING_MS;
            if (Integer.parseInt(line[1]) == Zvezda.LIVE && end <= now) end = now + 15 * MINUTE;   // running late
            busy |= game.start - now < 30 * MINUTE;
            found[k++] = new Object[] {line[0], football ? "⚽" : "🏀", game.start, end, RED, football ? "FotMob" : "EuroLeague", 20};
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

    /**
     * The saved answer for one sport, read again when due: FotMob's calendar every three hours (it says itself that it
     * changes every six), the EuroLeague feed every hour and every two minutes around a game, for the score.
     */
    private static synchronized Zvezda.Game read(SharedPreferences saved, String sport, boolean football, long now) {
        if (now >= saved.getLong(sport + "_due", 0)) {
            String body = NewsProvider.get(football ? Zvezda.FOOTBALL_CALENDAR : Ticker.gamesUrl(now));
            Zvezda.Game fresh = body.isEmpty() ? null : football ? Zvezda.fromCalendar(body, now) : Zvezda.fromEuroleague(body, now);
            long due = now + (body.isEmpty() ? 10 * MINUTE : football ? 3 * HOUR : HOUR);
            if (!football && fresh != null && fresh.start - now < 30 * MINUTE) due = now + 2 * MINUTE;
            SharedPreferences.Editor e = saved.edit().putLong(sport + "_due", due);
            if (!body.isEmpty()) e.putString(sport + "_body", body);
            e.apply();
        }
        String body = saved.getString(sport + "_body", "");
        return football ? Zvezda.fromCalendar(body, now) : Zvezda.fromEuroleague(body, now);
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
