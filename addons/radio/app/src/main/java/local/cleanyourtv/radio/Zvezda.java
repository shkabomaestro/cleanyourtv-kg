package local.cleanyourtv.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Zvezda's next football and basketball games, for the game panel under the clock on Home. Football from FotMob's
 * public calendar of the club's games (every competition: SuperLiga, the cup, UEFA, friendlies), the one people
 * subscribe to from Google or Apple calendars; basketball from the EuroLeague's own feed (Ticker). Sofascore, tried
 * first, refuses a client like this one (403). The text is built here, in Cyrillic, so the launcher only shows it.
 */
final class Zvezda {
    private Zvezda() {
    }

    /** FotMob's calendar of FK Crvena Zvezda (team 8687). */
    static final String FOOTBALL_CALENDAR = "https://pub.fotmob.com/prod/pub/api/v2/calendar/team/8687.ics";
    /** The line's state, for its colour on Home. */
    static final int LATER = 0, TODAY = 1, LIVE = 2;
    /** A game further off than this is not worth a line yet (a winter break). */
    static final long AHEAD_MS = 14 * 86_400_000L;
    /** How long after kick-off a game that never said it ended still counts as on. */
    static final long PLAYING_MS = 150 * 60_000L;

    /**
     * The next football game in FotMob's calendar that is on or ahead, or null. A played game carries its score in
     * the summary ("⚽️ FK Crvena Zvezda - OFK Beograd  (1-2)") and is skipped; the competition is the second line
     * of the description; a kick-off at 00:00 UTC is FotMob's way of saying the time is not set yet.
     */
    static Game fromCalendar(String ics, long now) {
        if (ics == null || !ics.contains("BEGIN:VCALENDAR")) return null;
        String text = ics.replace("\r\n", "\n").replace("\r", "\n").replace("\n ", "").replace("\n\t", "");
        Game best = null;
        boolean inEvent = false, inAlarm = false;
        String start = null, summary = null, description = null;
        for (String line : text.split("\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                inEvent = true;
                start = summary = description = null;
            } else if (line.equals("BEGIN:VALARM")) {
                inAlarm = true;
            } else if (line.equals("END:VALARM")) {
                inAlarm = false;
            } else if (line.equals("END:VEVENT")) {
                inEvent = false;
                Game g = event(start, summary, description, now);
                if (g != null && (best == null || g.start < best.start)) best = g;
            } else if (inEvent && !inAlarm) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon);
                if (key.indexOf(';') >= 0) key = key.substring(0, key.indexOf(';'));
                String value = line.substring(colon + 1);
                if (key.equals("DTSTART")) start = value;
                else if (key.equals("SUMMARY")) summary = value;
                else if (key.equals("DESCRIPTION")) description = value;
            }
        }
        return best;
    }

    private static Game event(String start, String summary, String description, long now) {
        if (start == null || summary == null) return null;
        long at;
        boolean timeKnown;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(start.length() > 8 ? "yyyyMMdd'T'HHmmss" : "yyyyMMdd",
                    Locale.ROOT);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            at = f.parse(start.replace("Z", "")).getTime();
            timeKnown = start.length() > 8 && !start.startsWith("000000", 9);
        } catch (Exception e) {
            return null;
        }
        if (at + PLAYING_MS < now) return null;
        String teams = summary.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
        if (teams.matches(".*\\(\\s*\\d+\\s*-\\s*\\d+\\s*\\)\\s*$")) return null;   // played: its score is in
        int dash = teams.indexOf(" - ");
        if (dash < 0) return null;
        String competition = "";
        if (description != null) {
            int nl = description.lastIndexOf("\\n");
            competition = nl >= 0 ? description.substring(nl + 2).trim() : "";
        }
        return new Game(at, teams.substring(0, dash).trim(), teams.substring(dash + 3).trim(), competition, false, -1, -1,
                timeKnown);
    }

    /** The next EuroLeague game from the feed (Ticker.games), on or ahead, with its score once it has started. */
    static Game fromEuroleague(String json, long now) {
        Ticker.Game next = null;
        for (Ticker.Game g : Ticker.games(json)) {
            if (!g.played && g.start + PLAYING_MS > now && (next == null || g.start < next.start)) next = g;
        }
        if (next == null) return null;
        boolean started = next.start <= now;
        return new Game(next.start, next.home, next.away, "Euroleague", false, started ? next.homeScore : -1,
                started ? next.awayScore : -1, true);
    }

    static final class Game {
        final long start;
        final String home, away, competition;
        final boolean live, timeKnown;
        final int homeScore, awayScore;

        Game(long start, String home, String away, String competition, boolean live, int homeScore, int awayScore,
             boolean timeKnown) {
            this.start = start;
            this.home = home;
            this.away = away;
            this.competition = competition;
            this.live = live;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.timeKnown = timeKnown;
        }
    }

    private static final String[] DAYS = {"нед", "пон", "уто", "сре", "чет", "пет", "суб"};

    /**
     * {text, state} for Home, or null when there is no game within two weeks. "суб 20:00 · Звезда – Партизан ·
     * Суперлига"; on the day "данас" or "вечерас"; while on "УЖИВО 1:0 · ...".
     */
    static String[] line(Game g, boolean football, long now) {
        if (g == null) return null;
        String home = football ? footballClub(g.home) : basketballClub(g.home);
        String away = football ? footballClub(g.away) : basketballClub(g.away);
        String tail = home + " – " + away + competition(g.competition, football);
        boolean on = g.live || (g.start <= now && now - g.start < PLAYING_MS);
        if (on) {
            String score = g.homeScore >= 0 && g.awayScore >= 0 ? " " + g.homeScore + ":" + g.awayScore : "";
            return new String[]{"УЖИВО" + score + " · " + tail, String.valueOf(LIVE)};
        }
        if (g.start - now > AHEAD_MS) return null;
        Calendar c = Calendar.getInstance(Ticker.BELGRADE);
        c.setTimeInMillis(g.start);
        Calendar today = Calendar.getInstance(Ticker.BELGRADE);
        today.setTimeInMillis(now);
        int days = (int) Math.round((midnight(c) - midnight(today)) / 86_400_000.0);
        String when;
        if (days == 0) when = c.get(Calendar.HOUR_OF_DAY) < 17 ? "данас" : "вечерас";
        else if (days == 1) when = "сутра";
        else if (days < 7) when = DAYS[c.get(Calendar.DAY_OF_WEEK) - 1];
        else when = String.format(Locale.ROOT, "%s %d.%d.", DAYS[c.get(Calendar.DAY_OF_WEEK) - 1],
                    c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1);
        if (!g.timeKnown) {   // the day is set, the hour not yet
            if (days == 0 || days == 1) when = days == 0 ? "данас" : "сутра";
            else when = String.format(Locale.ROOT, "%s %d.%d.", DAYS[c.get(Calendar.DAY_OF_WEEK) - 1],
                    c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1);
            return new String[]{when + " · " + tail, String.valueOf(days == 0 ? TODAY : LATER)};
        }
        String text = String.format(Locale.ROOT, "%s %02d:%02d · %s", when, c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE), tail);
        return new String[]{text, String.valueOf(days == 0 ? TODAY : LATER)};
    }

    private static long midnight(Calendar c) {
        Calendar m = (Calendar) c.clone();
        m.set(Calendar.HOUR_OF_DAY, 0);
        m.set(Calendar.MINUTE, 0);
        m.set(Calendar.SECOND, 0);
        m.set(Calendar.MILLISECOND, 0);
        return m.getTimeInMillis();
    }

    /** " · Суперлига": competitions as Serbian papers name them, empty when Sofascore gives none. */
    static String competition(String name, boolean football) {
        String f = News.fold(name);
        if (f.isEmpty()) return "";
        String[][] known = {{"champions league", "Лига шампиона"}, {"europa league", "Лига Европе"},
                {"conference league", "Лига конференција"}, {"superliga", "Суперлига"}, {"super liga", "Суперлига"},
                {"euroleague", "Евролига"}, {"aba", "АБА лига"}, {"korac", "Куп Радивоја Кораћа"},
                {"cup", "Куп Србије"}, {"kup", "Куп Србије"}, {"friendl", "пријатељска"}, {"serbia", football ? "Суперлига" : "КЛС"}};
        for (String[] k : known) if (f.contains(k[0])) return " · " + k[1];
        return " · " + News.cyrillic(name);
    }

    /** Football clubs: Zvezda and the SuperLiga as the papers write them; a foreign club in its own spelling. */
    static String footballClub(String name) {
        String plain = name.replaceAll("^(FK|FC|SFK)\\s+", "").trim();
        String f = News.fold(name);
        String[][] known = {{"zvezda", "Звезда"}, {"partizan", "Партизан"}, {"radnicki 1923", "Раднички 1923"},
                {"radnicki kragujevac", "Раднички 1923"}, {"radnicki nis", "Раднички Ниш"}, {"vojvodina", "Војводина"},
                {"cukaricki", "Чукарички"}, {"tsc", "ТСЦ"}, {"backa topola", "ТСЦ"}, {"novi pazar", "Нови Пазар"},
                {"spartak", "Спартак"}, {"napredak", "Напредак"}, {"mladost", "Младост"}, {"ofk beograd", "ОФК Београд"},
                {"imt", "ИМТ"}, {"zeleznicar", "Железничар"}, {"javor", "Јавор"}, {"radnik", "Радник"},
                {"jedinstvo", "Јединство"}, {"zemun", "Земун"}, {"macva", "Мачва"}, {"tekstilac", "Текстилац"}, {"real madrid", "Реал Мадрид"}, {"barcelona", "Барселона"},
                {"bayern", "Бајерн"}, {"inter milan", "Интер"}, {"internazionale", "Интер"}, {"ac milan", "Милан"}, {"juventus", "Јувентус"}};
        for (String[] k : known) if (f.contains(k[0])) return k[1];
        return plain;   // a foreign club as it writes itself: letter by letter "Lille" would read "Лилле"
    }

    /** Basketball clubs: the ABA league first, then the EuroLeague names Ticker already knows. */
    static String basketballClub(String name) {
        String f = News.fold(name);
        String[][] known = {{"zvezda", "Звезда"}, {"partizan", "Партизан"}, {"mega", "Мега"}, {"fmp", "ФМП"},
                {"buducnost", "Будућност"}, {"cedevita", "Цедевита Олимпија"}, {"zadar", "Задар"}, {"cibona", "Цибона"},
                {"igokea", "Игокеа"}, {"spartak", "Спартак"}, {"borac", "Борац"}, {"krka", "Крка"}, {"split", "Сплит"},
                {"ilirija", "Илирија"}, {"bosna", "Босна"}, {"derby", "СЦ Дерби"}, {"vienna", "Беч"}};
        for (String[] k : known) if (f.contains(k[0])) return k[1];
        return Ticker.clubName(name.replaceAll("^(KK|BC|BK)\\s+", ""));
    }
}
