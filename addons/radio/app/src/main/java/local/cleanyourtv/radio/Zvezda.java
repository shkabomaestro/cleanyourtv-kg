package local.cleanyourtv.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Zvezda's next football and basketball games, for the reminder under the weather on Home: every competition
 * (SuperLiga, the cup, UEFA, the EuroLeague, the ABA league, the Serbian league), from Sofascore's public
 * team schedule. The text is built here, in Cyrillic, so the launcher only shows it.
 */
final class Zvezda {
    private Zvezda() {
    }

    static final int FOOTBALL_TEAM = 5149, BASKETBALL_TEAM = 57183;
    /** The line's state, for its colour on Home. */
    static final int LATER = 0, TODAY = 1, LIVE = 2;
    /** A game further off than this is not worth a line yet (a winter break). */
    static final long AHEAD_MS = 14 * 86_400_000L;
    /** How long after kick-off a game that never said it ended still counts as on. */
    static final long PLAYING_MS = 150 * 60_000L;

    static String url(int team) {
        return "https://api.sofascore.com/api/v1/team/" + team + "/events/next/0";
    }

    /** The first game in Sofascore's answer that is on or ahead, or null. */
    static Game next(String json, long now) {
        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) return null;
            Game best = null;
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i);
                long start = e.optLong("startTimestamp", 0) * 1000;
                JSONObject status = e.optJSONObject("status");
                String type = status == null ? "" : status.optString("type", "");
                if (start == 0 || "finished".equals(type) || "canceled".equals(type) || "postponed".equals(type)) continue;
                boolean live = "inprogress".equals(type);
                if (!live && start + PLAYING_MS < now) continue;
                if (best != null && start >= best.start) continue;
                JSONObject home = e.optJSONObject("homeTeam"), away = e.optJSONObject("awayTeam");
                if (home == null || away == null) continue;
                JSONObject tournament = e.optJSONObject("tournament");
                JSONObject unique = tournament == null ? null : tournament.optJSONObject("uniqueTournament");
                String competition = unique != null ? unique.optString("name", "")
                        : tournament != null ? tournament.optString("name", "") : "";
                best = new Game(start, home.optString("name", "?"), away.optString("name", "?"), competition, live,
                        score(e.optJSONObject("homeScore")), score(e.optJSONObject("awayScore")));
            }
            return best;
        } catch (Exception e) {
            return null;   // anything unexpected: no line rather than a wrong one
        }
    }

    private static int score(JSONObject s) {
        return s == null ? -1 : s.optInt("current", -1);
    }

    static final class Game {
        final long start;
        final String home, away, competition;
        final boolean live;
        final int homeScore, awayScore;

        Game(long start, String home, String away, String competition, boolean live, int homeScore, int awayScore) {
            this.start = start;
            this.home = home;
            this.away = away;
            this.competition = competition;
            this.live = live;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
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
        String plain = name.replaceAll("^(FK|FC|OFK |SFK)\\s+", "").trim();
        String f = News.fold(name);
        String[][] known = {{"zvezda", "Звезда"}, {"partizan", "Партизан"}, {"radnicki 1923", "Раднички 1923"},
                {"radnicki kragujevac", "Раднички 1923"}, {"radnicki nis", "Раднички Ниш"}, {"vojvodina", "Војводина"},
                {"cukaricki", "Чукарички"}, {"tsc", "ТСЦ"}, {"backa topola", "ТСЦ"}, {"novi pazar", "Нови Пазар"},
                {"spartak", "Спартак"}, {"napredak", "Напредак"}, {"mladost", "Младост"}, {"ofk beograd", "ОФК Београд"},
                {"imt", "ИМТ"}, {"zeleznicar", "Железничар"}, {"javor", "Јавор"}, {"radnik", "Радник"},
                {"jedinstvo", "Јединство"}, {"real madrid", "Реал Мадрид"}, {"barcelona", "Барселона"},
                {"bayern", "Бајерн"}, {"inter", "Интер"}, {"ac milan", "Милан"}, {"juventus", "Јувентус"}};
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
