package local.cleanyourtv.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Local addition: the lines the ticker shows before the headlines. Crvena zvezda's EuroLeague game (the next one, the
 * score while it is on, the final score for a day after), the National Bank of Serbia's middle rate for the euro and
 * the dollar, and a line from Kragujevac's history. Each is one row {title, source, kind}: kind 2 is a game on now
 * (the ticker then asks every minute), 3 an information line.
 */
final class Ticker {
    private Ticker() { }

    static final int LIVE = 2, INFO = 3;
    static final TimeZone BELGRADE = TimeZone.getTimeZone("Europe/Belgrade");

    // ---- Crvena zvezda in the EuroLeague -----------------------------------------------------------------------

    /** The season code the EuroLeague's feed wants: E2026 for the season that starts in autumn 2026. */
    static String season(long now) {
        Calendar c = Calendar.getInstance(BELGRADE);
        c.setTimeInMillis(now);
        int year = c.get(Calendar.YEAR) - (c.get(Calendar.MONTH) < Calendar.AUGUST ? 1 : 0);
        return "E" + year;
    }

    static String gamesUrl(long now) {
        return "https://feeds.incrowdsports.com/provider/euroleague-feeds/v2/competitions/E/seasons/" + season(now)
                + "/games?TeamCode=RED";
    }

    /** One game of the feed, as far as the ticker needs it. */
    static final class Game {
        final long start;
        final String home, away;
        final int homeScore, awayScore;
        final boolean played, zvezdaHome;

        Game(long start, String home, String away, int homeScore, int awayScore, boolean played, boolean zvezdaHome) {
            this.start = start;
            this.home = home;
            this.away = away;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.played = played;
            this.zvezdaHome = zvezdaHome;
        }
    }

    /**
     * Zvezda's games in the feed's answer; anything unexpected in it gives none rather than a wrong line. The feed
     * as it answers in November 2026: data[] of {date, status ("confirmed", "result", ...), home: {code, name,
     * abbreviatedName, score}, away: {...}}. The older shape (local/road with a club inside) is still read.
     */
    static List<Game> games(String json) {
        List<Game> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject g = data.getJSONObject(i);
                JSONObject local = g.optJSONObject("home"), road = g.optJSONObject("away");
                if (local == null || road == null) {
                    local = g.optJSONObject("local");
                    road = g.optJSONObject("road");
                }
                if (local == null || road == null) continue;
                String home = club(local), away = club(road);
                boolean zvezdaHome = isZvezda(local), zvezdaAway = isZvezda(road);
                if (!zvezdaHome && !zvezdaAway) continue;
                long start = utc(g.optString("date", ""));
                if (start == 0) continue;
                boolean played = g.optBoolean("played", false) || "result".equalsIgnoreCase(g.optString("status", ""));
                out.add(new Game(start, home, away, local.optInt("score", 0), road.optInt("score", 0), played, zvezdaHome));
            }
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    /** Zvezda's crest as the feed links it (imageUrls.crest), or null. */
    static String crestUrl(String json) {
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject g = data.getJSONObject(i);
                for (String side : new String[] {"home", "away"}) {
                    JSONObject t = g.optJSONObject(side);
                    if (t == null || !isZvezda(t)) continue;
                    JSONObject images = t.optJSONObject("imageUrls");
                    String url = images == null ? "" : images.optString("crest", "");
                    if (url.startsWith("https://")) return url;
                }
            }
        } catch (Exception e) {
            // no crest from this answer
        }
        return null;
    }

    /** The team itself, or the club inside it in the older shape. */
    private static JSONObject team(JSONObject side) {
        JSONObject c = side.optJSONObject("club");
        return c != null ? c : side;
    }

    private static boolean isZvezda(JSONObject side) {
        JSONObject c = team(side);
        return "RED".equalsIgnoreCase(c.optString("code", "")) || News.fold(c.optString("name", "")).contains("zvezda");
    }

    private static String club(JSONObject side) {
        JSONObject c = team(side);
        String name = c.optString("abbreviatedName", c.optString("name", "?"));
        return clubName(name);
    }

    /** EuroLeague clubs as Serbian papers write them; any other name goes through the Cyrillic conversion. */
    static String clubName(String name) {
        String f = News.fold(name);
        String[][] known = {{"zvezda", "Звезда"}, {"partizan", "Партизан"}, {"olympiacos", "Олимпијакос"},
                {"olympiakos", "Олимпијакос"}, {"panathinaikos", "Панатинаикос"}, {"real madrid", "Реал Мадрид"},
                {"barcelona", "Барселона"}, {"fenerbahce", "Фенербахче"}, {"efes", "Ефес"}, {"zalgiris", "Жалгирис"},
                {"milan", "Олимпија Милано"}, {"olimpia", "Олимпија Милано"}, {"bayern", "Бајерн"},
                {"monaco", "Монако"}, {"virtus", "Виртус"}, {"bologna", "Виртус"}, {"asvel", "Асвел"},
                {"villeurbanne", "Асвел"}, {"paris", "Париз"}, {"baskonia", "Басконија"}, {"maccabi", "Макаби"},
                {"hapoel", "Хапоел"}, {"dubai", "Дубаи"}, {"valencia", "Валенсија"}, {"alba", "Алба"},
                {"lyon", "Асвел"}, {"monacco", "Монако"}};
        for (String[] k : known) if (f.contains(k[0])) return k[1];
        return News.cyrillic(name);
    }

    /** "2026-11-20T19:30:00Z", with an offset, or with none (then UTC, as the feed's own times are); 0 if not a date. */
    static long utc(String text) {
        if (text.length() < 19) return 0;
        String base = text.substring(0, 19);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date d = f.parse(base, new ParsePosition(0));
        if (d == null) return 0;
        long at = d.getTime();
        String rest = text.substring(19).replaceAll("^\\.\\d+", "");
        if (rest.matches("[+-]\\d\\d:?\\d\\d")) {
            int sign = rest.charAt(0) == '-' ? -1 : 1;
            String digits = rest.substring(1).replace(":", "");
            int minutes = Integer.parseInt(digits.substring(0, 2)) * 60 + Integer.parseInt(digits.substring(2, 4));
            at -= sign * minutes * 60_000L;
        }
        return at;
    }

    private static final String[] DAYS = {"нед", "пон", "уто", "сре", "чет", "пет", "суб"};

    /**
     * The line for Zvezda right now, or null: the score while a game is on (up to three hours after the tip-off),
     * the final score for a day after it, otherwise the next game within a week with its day and time.
     */
    static String[] zvezda(List<Game> games, long now) {
        Game live = null, last = null, next = null;
        for (Game g : games) {
            if (!g.played && g.start <= now && now - g.start < 3 * 3_600_000L) live = g;
            else if (g.played && now - g.start < 27 * 3_600_000L && (last == null || g.start > last.start)) last = g;
            else if (!g.played && g.start > now && g.start - now < 7 * 86_400_000L && (next == null || g.start < next.start)) next = g;
        }
        if (live != null) {
            return new String[]{"УЖИВО · Евролига: " + live.home + " " + live.homeScore + ":" + live.awayScore + " " + live.away,
                    String.valueOf(LIVE)};
        }
        if (last != null) {
            return new String[]{"Евролига, крај: " + last.home + " " + last.homeScore + ":" + last.awayScore + " " + last.away,
                    String.valueOf(INFO)};
        }
        if (next != null) {
            Calendar c = Calendar.getInstance(BELGRADE);
            c.setTimeInMillis(next.start);
            Calendar today = Calendar.getInstance(BELGRADE);
            today.setTimeInMillis(now);
            int days = dayNumber(c) - dayNumber(today);
            String when = days == 0 ? "вечерас" : days == 1 ? "сутра" : DAYS[c.get(Calendar.DAY_OF_WEEK) - 1];
            if (days == 0 && c.get(Calendar.HOUR_OF_DAY) < 17) when = "данас";
            return new String[]{String.format(Locale.ROOT, "Евролига, %s у %02d:%02d: %s – %s", when,
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), next.home, next.away), String.valueOf(INFO)};
        }
        return null;
    }

    private static int dayNumber(Calendar c) {
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR);
    }

    // ---- the National Bank of Serbia's middle rate -------------------------------------------------------------

    static final String EUR = "https://kurs.resenje.org/api/v1/currencies/eur/rates/today",
            USD = "https://kurs.resenje.org/api/v1/currencies/usd/rates/today";

    static double middle(String json) {
        try {
            return new JSONObject(json).optDouble("exchange_middle", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static String rates(double eur, double usd) {
        if (eur <= 0 && usd <= 0) return null;
        StringBuilder s = new StringBuilder("Курс НБС:");
        if (eur > 0) s.append(String.format(Locale.ROOT, " евро %.2f дин.", eur).replace('.', ','));
        if (eur > 0 && usd > 0) s.append(" ·");
        if (usd > 0) s.append(String.format(Locale.ROOT, " долар %.2f дин.", usd).replace('.', ','));
        return s.toString().replace("дин,", "дин.");
    }

    // ---- Kragujevac's history ----------------------------------------------------------------------------------

    /** {month, day, text}: shown as "На данашњи дан" on that date (month and day 0: any day). */
    static final String[][] HISTORY = {
            {"2", "15", "1835. у Крагујевцу је донет Сретењски устав, први устав Србије."},
            {"2", "15", "1876. у Крагујевцу је подигнут Црвени барјак."},
            {"0", "0", "Кнез Милош Обреновић прогласио је Крагујевац престоницом Србије 1818. године."},
            {"0", "0", "Крагујевац је био престоница Србије до 1841, када је она пренета у Београд."},
            {"0", "0", "Прва крагујевачка гимназија основана је 1833. и једна је од најстаријих у Србији."},
            {"0", "0", "Књажевско-српски театар, основан 1835, прво је позориште у Србији."},
            {"0", "0", "Лицеј, претеча Београдског универзитета, основан је у Крагујевцу 1838. године."},
            {"0", "0", "Тополивница, основана 1853, почетак је крагујевачке индустрије."},
            {"0", "0", "Стару цркву у Крагујевцу подигао је кнез Милош 1818. године."},
            {"0", "0", "У Крагујевцу су излазиле „Новине србске“ док је град био престоница."},
            {"0", "0", "Производња „Фиће“, Заставе 750, почела је у Крагујевцу 1955. године."},
            {"0", "0", "Производња „Југа“ почела је у Крагујевцу 1980. године."},
            {"0", "0", "Универзитет у Крагујевцу основан је 1976. године."},
            {"0", "0", "Фудбалски клуб Раднички основан је 1923. године."},
            {"0", "0", "Име Крагујевца потиче од крагуја, птице грабљивице."},
    };

    /** Today's line: an anniversary if today has one, otherwise one of the others, a different one each day. */
    static String history(long now) {
        Calendar c = Calendar.getInstance(BELGRADE);
        c.setTimeInMillis(now);
        int month = c.get(Calendar.MONTH) + 1, day = c.get(Calendar.DAY_OF_MONTH);
        StringBuilder today = new StringBuilder();
        List<String> any = new ArrayList<>();
        for (String[] h : HISTORY) {
            if (Integer.parseInt(h[0]) == month && Integer.parseInt(h[1]) == day) today.append(today.length() > 0 ? " " : "").append(h[2]);
            else if ("0".equals(h[0])) any.add(h[2]);
        }
        if (today.length() > 0) return "На данашњи дан " + today;
        return "Из историје Крагујевца: " + any.get(c.get(Calendar.DAY_OF_YEAR) % any.size());
    }
}
