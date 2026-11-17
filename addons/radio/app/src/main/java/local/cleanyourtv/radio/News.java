package local.cleanyourtv.radio;

import java.text.Normalizer;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The headlines for the launcher's news ticker (kajron): read from RTS, Mondo and Mozzart Sport, with no crime
 * stories (crna hronika) and no betting tips, the sport first, everything in Serbian Cyrillic. Plain Java with no
 * Android in it, so the whole choice can be checked on a computer; NewsProvider does the downloading.
 */
final class News {
    private News() { }

    /** One feed: its address, the name the ticker shows, and whether everything in it is sport. */
    static final class Feed {
        final String url, source;
        final boolean sport;

        Feed(String url, String source, boolean sport) {
            this.url = url;
            this.source = source;
            this.sport = sport;
        }
    }

    static final List<Feed> FEEDS = List.of(
            new Feed("https://www.mozzartsport.com/rss/0.xml", "Моцарт спорт", true),
            new Feed("https://www.rts.rs/sport/rss.html", "РТС", true),
            new Feed("https://mondo.rs/rss/644/Sport", "Мондо", true),
            new Feed("https://www.rts.rs/vesti/rss.html", "РТС", false),
            new Feed("https://mondo.rs/rss/637/Info/Srbija", "Мондо", false),
            new Feed("https://mondo.rs/rss/638/Info/Svet", "Мондо", false));

    static final class Item {
        final String title, link, source, categories;
        final boolean sport;
        final long at;
        double score;
        Set<String> words;

        Item(String title, String link, String source, String categories, boolean sport, long at) {
            this.title = title;
            this.link = link;
            this.source = source;
            this.categories = categories;
            this.sport = sport;
            this.at = at;
        }
    }

    /** How many of each the ticker gets, and how old a headline may be. */
    static final int MAX_SPORT = 14, MAX_NEWS = 8;
    static final long MAX_AGE_MS = 30 * 3_600_000L;

    // ---- reading a feed --------------------------------------------------------------------------------------

    private static final Pattern ITEM = Pattern.compile("<item[\\s>](.*?)</item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** The items of one RSS document; anything that is not RSS gives none. */
    static List<Item> parse(String xml, Feed feed) {
        List<Item> items = new ArrayList<>();
        if (xml == null) return items;
        Matcher m = ITEM.matcher(xml);
        while (m.find() && items.size() < 80) {
            String block = m.group(1);
            String title = clean(tag(block, "title"));
            if (title.isEmpty()) continue;
            StringBuilder categories = new StringBuilder();
            Matcher c = Pattern.compile("<category[^>]*>(.*?)</category>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(block);
            while (c.find()) categories.append(clean(c.group(1))).append(' ');
            items.add(new Item(title, clean(tag(block, "link")), feed.source, categories.toString().trim(), feed.sport,
                    date(clean(tag(block, "pubDate")))));
        }
        return items;
    }

    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(block);
        return m.find() ? m.group(1) : "";
    }

    /** CDATA unwrapped, tags dropped, entities decoded, white space collapsed. */
    static String clean(String raw) {
        String s = raw.replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1").replaceAll("(?s)<[^>]*>", " ");
        Matcher e = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").matcher(s);
        StringBuilder out = new StringBuilder();
        while (e.find()) {
            String code = e.group(1), with;
            if (code.startsWith("#x")) with = new String(Character.toChars(Integer.parseInt(code.substring(2), 16)));
            else if (code.startsWith("#")) with = new String(Character.toChars(Integer.parseInt(code.substring(1))));
            else with = ENTITIES.getOrDefault(code, e.group());
            e.appendReplacement(out, Matcher.quoteReplacement(with));
        }
        e.appendTail(out);
        return out.toString().replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static final Map<String, String> ENTITIES = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"",
            "apos", "'", "nbsp", " ", "bdquo", "„", "ldquo", "“", "rdquo", "”", "ndash", "–");

    /** RFC 822 as the feeds write it ("Tue, 17 Nov 2026 15:42:18 +0100", "... GMT", sometimes no weekday); 0 if not. */
    static long date(String text) {
        for (String pattern : new String[]{"EEE, d MMM yyyy HH:mm:ss z", "d MMM yyyy HH:mm:ss z", "EEE, d MMM yyyy HH:mm z"}) {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setLenient(true);
            Date d = format.parse(text.trim(), new ParsePosition(0));
            if (d != null) return d.getTime();
        }
        return 0;
    }

    // ---- what stays out ---------------------------------------------------------------------------------------

    /** Word beginnings (in folded Latin) that mark a crime or accident story. Matched at the start of a word only. */
    private static final String[] CRIME = {
            "ubist", "ubij", "ubio", "ubila", "ubile", "ubili", "ubic", "izbod", "uboden", "ubod", "nozem", "pucnjav",
            "pucao", "pucala", "pucali", "upucan", "ranjen", "uhapsen", "uhapsi", "hapsenj", "priveden", "pritvor",
            "osumnjic", "optuzen", "optuzni", "krivicn", "kradj", "ukrad", "pljack", "oplja", "razbojn", "silov",
            "zlostavlj", "nasilj", "zlocin", "kriminal", "narkot", "kokain", "marihuan", "heroin", "drog", "mafij",
            "poginu", "pogibi", "nastrada", "preminu", "umro", "umrla", "smrt", "mrtv", "bezivotn", "samoubi", "sudar",
            "saobracajn", "nesrec", "pozar", "izgore", "eksploz", "otmic", "oteo", "otet", "nestal", "tragedij",
            "tragicn", "jeziv", "horor", "krvav", "pretuk", "pretuc", "talac", "taoc", "pedofil", "zatvorsk",
            "policij", "policaj", "istrag", "tuzilast", "osuden", "teroris", "atentat"};
    /** Whole words of the same kind that would match too much as beginnings. */
    private static final Set<String> CRIME_WORDS = Set.of("udes", "udesu", "udesa", "zatvor", "zatvoru", "zatvora", "telo",
            "tela", "les", "lesa", "ubica", "ubice", "ubicu", "presuda", "presudu", "presude", "presudom");
    /** Betting tips and offers (Mozzart is a bookmaker's sports site). */
    private static final String[] BETTING = {"kvot", "tiket", "kladj", "kladi", "uplat", "dzekpot", "bonus", "tip dana"};

    /** True when a headline must not reach the ticker. */
    static boolean blocked(Item item) {
        String where = fold(item.link + " " + item.categories);
        if (where.contains("hronika") || where.contains("/zabava/") || where.contains("/magazin/")
                || where.contains("horoskop")) {
            return true;
        }
        List<String> words = words(item.title);
        for (String w : words) {
            if (CRIME_WORDS.contains(w)) return true;
            for (String stem : CRIME) if (w.startsWith(stem)) return true;
            for (String stem : BETTING) if (!stem.contains(" ") && w.startsWith(stem)) return true;
        }
        String flat = " " + String.join(" ", words) + " ";
        for (String stem : BETTING) if (stem.contains(" ") && flat.contains(" " + stem)) return true;
        return false;
    }

    // ---- what comes first -------------------------------------------------------------------------------------

    /** Folded-Latin word beginnings that make a headline weigh more, with how much. */
    private static final Map<String, Double> WEIGHTS = new HashMap<>();

    static {
        // Sport: the clubs, the players and the competitions people here follow; the town's own club first.
        for (String w : new String[]{"radnick", "kragujev"}) WEIGHTS.put(w, 1.4);
        for (String w : new String[]{"zvezd", "partizan", "jokic", "djokovic", "dokovic", "reprezentacij", "orlov", "orlic",
                "srbi"}) {
            WEIGHTS.put(w, 1.0);
        }
        for (String w : new String[]{"evrolig", "nba", "aba", "superlig", "sampion", "mundijal", "olimpij", "finale",
                "finalu", "finala", "polufinal", "titul", "trofej", "medalj", "zlat", "rekord", "derbi", "pobed", "trijumf",
                "slem", "vimbldon", "garos", "real", "barsel", "liverpul", "junajted", "siti", "bajern", "pobedni"}) {
            WEIGHTS.put(w, 0.6);
        }
        for (String w : new String[]{"zvanicno", "transfer", "potpisa", "dolazi", "odlazi"}) WEIGHTS.put(w, 0.35);
        // News: the country and the town.
        for (String w : new String[]{"vlada", "predsedni", "skupstin", "evrop", "eu", "nato", "ujedinjen"}) WEIGHTS.put(w, 0.3);
    }

    /** The headlines for the ticker: blocked ones out, one of each story, the best first, two sport to one news. */
    static List<Item> pick(List<Item> all, long now) {
        List<Item> fresh = new ArrayList<>();
        for (Item item : all) {
            if (item.at > now + 3_600_000L) continue;   // a date in the future: a broken clock somewhere, skip it
            if (item.at != 0 && now - item.at > MAX_AGE_MS) continue;
            if (blocked(item)) continue;
            item.words = significant(item.title);
            item.score = score(item, now);
            fresh.add(item);
        }
        // The same story in two places is a big story: it counts more, and is shown once (RTS's Cyrillic wording).
        List<Item> kept = new ArrayList<>();
        for (Item item : fresh) {
            Item same = null;
            for (Item k : kept) {
                if (k.sport == item.sport && overlap(k.words, item.words) >= 3) {
                    same = k;
                    break;
                }
            }
            if (same == null) {
                kept.add(item);
            } else if (!same.source.equals(item.source)) {
                same.score += 0.8;
                if (item.source.startsWith("РТС") && !same.source.startsWith("РТС")) {
                    item.score = Math.max(item.score, same.score);
                    kept.set(kept.indexOf(same), item);
                }
            }
        }
        List<Item> sport = new ArrayList<>(), news = new ArrayList<>();
        for (Item item : kept) (item.sport ? sport : news).add(item);
        Collections.sort(sport, (a, b) -> Double.compare(b.score, a.score));
        Collections.sort(news, (a, b) -> Double.compare(b.score, a.score));
        sport = new ArrayList<>(sport.subList(0, Math.min(MAX_SPORT, sport.size())));
        news = new ArrayList<>(news.subList(0, Math.min(MAX_NEWS, news.size())));
        List<Item> out = new ArrayList<>();
        int s = 0, n = 0;
        while (s < sport.size() || n < news.size()) {
            for (int i = 0; i < 2 && s < sport.size(); i++) out.add(sport.get(s++));
            if (n < news.size()) out.add(news.get(n++));
        }
        return out;
    }

    static double score(Item item, long now) {
        double hours = item.at == 0 ? 12 : Math.max(0, (now - item.at) / 3_600_000.0);
        double score = 1.0 + Math.exp(-hours / 8.0) * 1.5;
        if (item.sport) score += 0.6;
        Set<String> counted = new HashSet<>();
        for (String w : words(item.title)) {
            for (Map.Entry<String, Double> e : WEIGHTS.entrySet()) {
                boolean whole = e.getKey().length() <= 3;   // "nba", "aba", "eu": whole words only
                if ((whole ? w.equals(e.getKey()) : w.startsWith(e.getKey())) && counted.add(e.getKey())) score += e.getValue();
            }
        }
        return score;
    }

    private static final Set<String> STOP = Set.of("posle", "protiv", "nakon", "zbog", "kako", "danas", "sutra", "jedan",
            "jedna", "jedno", "evropa", "srbija", "srbije", "godine", "godina", "prvi", "prva", "novi", "nova", "samo",
            "ovako", "ovaj", "ova", "ovo", "kada", "gde", "sta", "koji", "koja", "koje", "biti", "bice", "bila", "bilo");

    private static Set<String> significant(String title) {
        Set<String> out = new HashSet<>();
        for (String w : words(title)) {
            if (w.length() >= 4 && !STOP.contains(w)) out.add(w.length() > 6 ? w.substring(0, 6) : w);   // rough stem
        }
        return out;
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String w : a) if (b.contains(w)) n++;
        return n;
    }

    // ---- scripts ----------------------------------------------------------------------------------------------

    private static final String CYR = "абвгдђежзијклљмнњопрстћуфхцчџш";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "dj", "e", "z", "z", "i", "j", "k", "l", "lj", "m", "n",
            "nj", "o", "p", "r", "s", "t", "c", "u", "f", "h", "c", "c", "dz", "s"};

    /** Lower case, Cyrillic to Latin, no diacritics (đ is dj): one spelling to match words in. */
    static String fold(String text) {
        StringBuilder out = new StringBuilder();
        for (char ch : text.toLowerCase(Locale.ROOT).toCharArray()) {
            int i = CYR.indexOf(ch);
            if (i >= 0) out.append(LAT[i]);
            else if (ch == 'đ') out.append("dj");
            else out.append(ch);
        }
        return Normalizer.normalize(out, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String w : fold(text).split("[^a-z0-9]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Names the feeds write in Latin that Serbian Cyrillic spells its own way. */
    private static final Map<String, String> NAMES = Map.of("Mozzart", "Моцарт", "MOZZART", "МОЦАРТ");
    /** Word beginnings where nj, lj or dž are two letters, not one (injekcija, konjunktura, nadživeti, podžupan). */
    private static final String[] SPLIT = {"injek", "konjun", "nadži", "podži", "podžu", "odžal", "nadžn"};

    /**
     * Serbian Latin to Cyrillic, word by word. Words with q, w, x or y (Wi-Fi, NewYork, Xbox) are foreign and stay
     * as written, as Cyrillic papers print them; so do numbers and punctuation. RTS's own Cyrillic passes untouched.
     */
    static String cyrillic(String text) {
        text = Normalizer.normalize(text, Normalizer.Form.NFC);   // č as one letter, not c plus a mark
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\\p{L}+(?:-\\p{L}+)*").matcher(text);   // "Wi-Fi" is one word
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            out.append(word(m.group()));
            last = m.end();
        }
        return out.append(text.substring(last)).toString();
    }

    private static String word(String w) {
        if (NAMES.containsKey(w)) return NAMES.get(w);
        String lower = w.toLowerCase(Locale.ROOT);
        for (char ch : lower.toCharArray()) {
            if (ch == 'q' || ch == 'w' || ch == 'x' || ch == 'y') return w;
            if (ch > 0x3FF && ch < 0x500) return w;   // already Cyrillic
        }
        boolean split = false;
        for (String s : SPLIT) if (lower.startsWith(s)) split = true;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            char next = i + 1 < w.length() ? w.charAt(i + 1) : 0;
            char lo = Character.toLowerCase(ch), nlo = Character.toLowerCase(next);
            boolean upper = Character.isUpperCase(ch);
            String two = null;
            if (!split && lo == 'l' && nlo == 'j') two = "љ";
            else if (!split && lo == 'n' && nlo == 'j') two = "њ";
            else if (!split && lo == 'd' && nlo == 'ž') two = "џ";
            if (two != null) {
                out.append(upper ? two.toUpperCase(Locale.ROOT) : two);
                i++;
                continue;
            }
            int at = "abcčćdđefghijklmnoprsštuvzž".indexOf(lo);
            if (at < 0) {
                out.append(ch);
                continue;
            }
            String c = String.valueOf("абцчћдђефгхијклмнопрсштувзж".charAt(at));
            out.append(upper ? c.toUpperCase(Locale.ROOT) : c);
        }
        return out.toString();
    }
}
