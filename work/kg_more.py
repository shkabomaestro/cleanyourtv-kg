"""Finds 50 more cheerful, colourful Kragujevac photos on Wikimedia Commons for the Kragujevac theme, at real 1080p.

    tools\\venv\\Scripts\\python.exe -I work\\kg_more.py            (Windows)
    tools/venv/bin/python -I work/kg_more.py                       (Linux, macOS)

What it does, in order:
  1. collects candidates: the files of the Kragujevac categories on Commons (two levels down), a few searches,
     and the hand-picked titles in SEEDS;
  2. drops what is not wanted: memorials, the October 1941 museum and its messages, cemeteries, camps, maps and
     coats of arms, portraits (taller than wide), anything too small for 1920x1080 after the 16:9 crop, and every
     photo the theme already uses (kg-hd-titles.txt);
  3. downloads a 640 px preview of each and scores it: colourful and bright first, sharp, not grey; near-identical
     shots (the same building from the same spot) and more than three of one subject are left out;
  4. keeps the best 50 (up to eight colourful night shots among them, for the night group), downloads them at
     1920 px (3840 for a panorama) into work/kg-more/, and writes work/kg-more/index.tsv and the overview work/kragujevac-nove.jpg.

Don't like one? Put its number from the overview (or its Commons title) on a line of work/kg-more-skip.txt and run
this again: it picks another in its place. Then work/kg_grade.py grades them with the rest of the theme.
Needs curl (Windows 10 and newer have it) and Pillow (tools/venv). Takes some minutes: Commons is asked politely.
"""
import hashlib
import json
import math
import re
import subprocess
import sys
import time
import unicodedata
import urllib.parse
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageStat

HERE = Path(__file__).resolve().parent
OUT = HERE / "kg-more"
API = "https://commons.wikimedia.org/w/api.php"
UA = "cleanyourtv-kg-wallpapers/1.1 (personal TV theme; contact via github.com/shkabomaestro)"
WANT = 50
MAX_NIGHT = 8
PER_SUBJECT = 4

ROOT_CATEGORIES = ["Kragujevac", "Views of Kragujevac", "Buildings in Kragujevac", "Streets in Kragujevac",
                   "Parks in Kragujevac", "Churches in Kragujevac", "Squares in Kragujevac", "Kragujevac at night",
                   "Culture of Kragujevac", "Urbanističke celine (Kragujevac)", "Kragujevac Cathedral",
                   "Stara crkva (Kragujevac)", "Narodnih Heroja Square (Kragujevac)", "Lepenica (river)"]
QUERIES = ["Kragujevac park", "Kragujevac jezero", "Kragujevac panorama", "Kragujevac centar", "Kragujevac ulica",
           "Kragujevac proleće", "Kragujevac jesen", "Kragujevac zima sneg", "Kragujevac zalazak", "Kragujevac festival",
           "Kragujevac Arsenal fest", "Kragujevac noću svetla", "Kragujevac Nova godina", "Крагујевац",
           "Крагујевац центар", "Крагујевац парк", "Kragujevac stadion", "Kragujevac fontana", "Kragujevac aerial",
           "Šumaričko jezero", "Bubanj jezero Kragujevac", "Kragujevac Milošev venac", "Kragujevac univerzitet"]
# Found by hand; they get a small head start in the score (they still have to pass every check).
SEEDS = [
    "File:Kragujevac 002.jpg", "File:Kragujevac 005.jpg", "File:Jezero Šumarice 1.jpg", "File:Jezero Bubanj.JPG",
    "File:Akvarijum Kragujevac 12.jpg", "File:Wiki Šumadija XI Kragujevac Cathedral 564.jpg",
    "File:Wiki Šumadija XI Kragujevac Cathedral 565.jpg", "File:Wiki Šumadija XI Kragujevac Cathedral 577.jpg",
    "File:Wiki Šumadija XI Stara crkva (Kragujevac) 1116 01.jpg", "File:Wiki Šumadija XI Stara crkva (Kragujevac) 599.jpg",
    "File:Wiki Šumadija XI Zgrada Prve kragujevačke gimnazije 677.jpg",
    "File:Wiki Šumadija XI Zgrada Prve kragujevačke gimnazije 689.jpg", "File:Зграда Прве крагујевачке гимназије.jpg",
    "File:Wiki Šumadija XI Kuće u Svetozara Markovića (Kragujevac) 692.jpg",
    "File:Wiki Šumadija XI Kuće u Svetozara Markovića (Kragujevac) 696.jpg",
    "File:Wiki Šumadija XI Buildings in Kragujevac 1127 02.jpg", "File:Улаз на стари бетонски пешачки мост у Крагујевцу.jpg",
    "File:Бетонски пешачки мост (4).jpg", "File:ПКИЦ15 Урбанистичка целина Крагујевца (1).jpg",
    "File:ПКИЦ15 Урбанистичка целина Крагујевца (10).jpg", "File:ПКИЦ15 Урбанистичка целина Крагујевца (15).jpg",
    "File:ПКИЦ15 Урбанистичка целина Крагујевца (17).jpg", "File:Урбанистичке целине Крагујевца 02.jpg",
    "File:Kralja Aleksandra I Karađorđevića, Kragujevac.jpg", "File:Kragujevac street scene 2.jpg",
    "File:Wiki Šumadija XI Cerovac (Kragujevac) 246.jpg", "File:Wiki Šumadija XI Velike Pčelice 795.jpg",
    "File:Wiki Šumadija XI Velike Pčelice 798.jpg",
]

# Folded (lower case, Latin, no marks) pieces of a title or category that rule a photo out.
NOT_WANTED = ["memorial", "spomen", "oktobar", "october", "massacre", "masakr", "streljan", "executed", "eternal glory",
              "not to be forgotten", "zaboravi", "broken wings", "v3", "sto za jednog", "gravestone", "groblj", "cemetery",
              "grob", "metino", "logor", "camp", "mutiny", "bubanj memorial", "bubanj-", "spomenik bubanj", "muzej 21",
              "21 oktob", "map", "mapa", "grb", "coat of arms", "flag", "zastava grada", "logo", "panoramio", "portrait",
              "births", "people", "players", "fk radnicki -", "1941", "1915", "1930", "wwii", "ww2", "funeral"]
NOT_WANTED_CATEGORIES = NOT_WANTED + ["monuments and memorials", "people of", "sports in", "maps of", "coats of arms",
                                      "flags of", "busts", "memorials", "museums"]


def fold(text):
    cyr = "абвгдђежзијклљмнњопрстћуфхцчџш"
    lat = ["a", "b", "v", "g", "d", "dj", "e", "z", "z", "i", "j", "k", "l", "lj", "m", "n", "nj", "o", "p", "r", "s",
           "t", "c", "u", "f", "h", "c", "c", "dz", "s"]
    out = "".join(lat[cyr.index(c)] if c in cyr else ("dj" if c == "đ" else c) for c in text.lower())
    return "".join(c for c in unicodedata.normalize("NFD", out) if not unicodedata.combining(c))


def wanted(name):
    f = fold(name)
    return not any(w in f for w in NOT_WANTED)


def curl(url, binary=False, tries=4):
    for attempt in range(tries):
        try:
            data = subprocess.run(["curl", "-sSL", "--fail", "--connect-timeout", "10", "--max-time", "60", "-A", UA,
                                   url], check=True, capture_output=True, timeout=70).stdout
            if data:
                return data if binary else json.loads(data)
        except Exception:
            pass
        if attempt + 1 < tries:
            time.sleep(3 * (attempt + 1))
    return b"" if binary else {}


def api(params):
    time.sleep(0.4)
    return curl(API + "?" + urllib.parse.urlencode({"format": "json", **params}))


INFO = {"prop": "imageinfo", "iiprop": "size|url|extmetadata|mime", "iiurlwidth": 640,
        "iiextmetadatafilter": "LicenseShortName|Artist|DateTimeOriginal"}


def harvest(params, found, why, pages=6):
    """Every page of one query (50 files at a time: Commons scales at most 50 previews per answer)."""
    cont = {}
    for _ in range(pages):
        data = api({**params, **cont})
        for page in data.get("query", {}).get("pages", {}).values():
            if "imageinfo" in page and page["title"] not in found:
                found[page["title"]] = (page["imageinfo"][0], why)
        cont = data.get("continue")
        if not cont:
            return


def subcategories(name):
    data = api({"action": "query", "list": "categorymembers", "cmtitle": "Category:" + name, "cmtype": "subcat",
                "cmlimit": 200})
    return [m["title"][9:] for m in data.get("query", {}).get("categorymembers", [])]


def collect():
    found = {}
    seen, level = set(), [c for c in ROOT_CATEGORIES]
    for depth in range(3):
        nxt = []
        for cat in level:
            if cat in seen or any(w in fold(cat) for w in NOT_WANTED_CATEGORIES):
                continue
            seen.add(cat)
            harvest({"action": "query", "generator": "categorymembers", "gcmtitle": "Category:" + cat,
                     "gcmtype": "file", "gcmlimit": 50, **INFO}, found, "c:" + cat)
            if depth < 2:
                nxt += subcategories(cat)
        level = nxt
        print(f"  categories, level {depth}: {len(seen)} read, {len(found)} files so far")
    for q in QUERIES:
        harvest({"action": "query", "generator": "search", "gsrsearch": q + " filetype:bitmap", "gsrnamespace": 6,
                 "gsrlimit": 40, **INFO}, found, "q:" + q, pages=1)
    for i in range(0, len(SEEDS), 40):
        harvest({"action": "query", "titles": "|".join(SEEDS[i:i + 40]), **INFO}, found, "seed", pages=1)
    for title in SEEDS:
        if title in found:
            found[title] = (found[title][0], "seed")
    print(f"  {len(found)} files found")
    return found


def big_enough(w, h):
    if w < h * 1.25:   # portrait or square: a 16:9 crop would keep too little of it
        return False
    cw, ch = (h * 16 // 9, h) if w * 9 >= h * 16 else (w, w * 9 // 16)
    return cw >= 1920 and ch >= 1080


def dhash(im):
    g = im.convert("L").resize((9, 8), Image.LANCZOS)
    px = list(g.getdata())
    return sum(1 << i for i in range(64) if px[(i // 8) * 9 + i % 8] > px[(i // 8) * 9 + i % 8 + 1])


def measure(im):
    """Colourfulness (Hasler and Süsstrunk), mean brightness, and sharpness (spread of the edges)."""
    im = im.convert("RGB")
    w, h = im.size
    nh = w * 9 // 16
    if nh < h:
        im = im.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
    small = im.resize((320, 180), Image.LANCZOS)
    rg, yb = [], []
    for r, g, b in small.getdata():
        rg.append(r - g)
        yb.append(0.5 * (r + g) - b)
    mean = lambda v: sum(v) / len(v)
    sd = lambda v, m: math.sqrt(sum((x - m) ** 2 for x in v) / len(v))
    mrg, myb = mean(rg), mean(yb)
    colour = math.sqrt(sd(rg, mrg) ** 2 + sd(yb, myb) ** 2) + 0.3 * math.sqrt(mrg ** 2 + myb ** 2)
    bright = ImageStat.Stat(small.convert("L")).mean[0]
    sharp = ImageStat.Stat(small.convert("L").filter(ImageFilter.FIND_EDGES)).stddev[0]
    return colour, bright, sharp, small


def subject(title):
    f = fold(re.sub(r"\.(jpe?g|png|tiff?)$", "", title[5:], flags=re.I))
    f = re.sub(r"wiki sumadija [xvi]+|pkic\d+|\(\d+\)|\d+", " ", f)
    words = [w for w in re.split(r"[^a-z]+", f) if len(w) > 2 and w not in ("kragujevac", "kragujevcu", "kragujevca", "jpg")]
    return " ".join(words[:3]) or "kragujevac"


CAPTIONS = [("cathedral", "Саборна црква"), ("saborna", "Саборна црква"), ("stara crkva", "Стара црква"),
            ("gimnazij", "Прва крагујевачка гимназија"), ("jezero sumarice", "Шумаричко језеро"),
            ("sumaricko", "Шумаричко језеро"), ("bubanj", "Језеро Бубањ"), ("akvarijum", "Акваријум Крагујевац"),
            ("svetozara markovica", "Улица Светозара Марковића"), ("kralja aleksandra", "Улица краља Александра I"),
            ("kralja petra", "Улица краља Петра I"), ("narodnih heroja", "Трг народних хероја"),
            ("radomira putnika", "Трг војводе Радомира Путника"), ("veliki park", "Велики парк"),
            ("lepenic", "Лепеница"), ("most", "Пешачки мост на Лепеници"), ("urbanist", "Стари центар града"),
            ("teatar", "Књажевско-српски театар"), ("amidz", "Амиџин конак"), ("milosev venac", "Милошев венац"),
            ("univerzitet", "Универзитет у Крагујевцу"), ("rektorat", "Универзитет у Крагујевцу"),
            ("cika dac", "Стадион „Чика Дача“"), ("stadion", "Стадион „Чика Дача“"), ("hala jezero", "Хала „Језеро“"),
            ("sportska hala", "Хала „Језеро“"), ("cerovac", "Церовац"), ("pcelice", "Велике Пчелице"),
            ("gruz", "Језеро Гружа"), ("arsenal", "Крагујевачки арсенал"), ("topolivnic", "Тополивница"),
            ("zastava", "Застава"), ("galerij", "Галерија"), ("skupstin", "Скупштина града"), ("fontan", "Фонтана"),
            ("ilina voda", "Илина вода"), ("djurdjevo brdo", "Ђурђево брдо"), ("aerial", "Крагујевац из ваздуха"),
            ("dron", "Крагујевац из ваздуха"), ("panoram", "Поглед на Крагујевац"), ("street", "Улица у центру града"),
            ("ulica", "Улица у центру града"), ("park", "Парк у Крагујевцу")]


def caption(title):
    f = fold(title)
    for key, text in CAPTIONS:
        if key in f:
            return text
    return "Крагујевац"


def slug(title):
    base = re.sub(r"[^a-z0-9]+", "-", fold(title[5:].rsplit(".", 1)[0])).strip("-")[:40]
    return "nova-" + base + "-" + hashlib.sha1(title.encode()).hexdigest()[:5]


def main():
    OUT.mkdir(exist_ok=True)
    used = set()
    titles_file = HERE / "kg-hd-titles.txt"
    if titles_file.exists():
        used = {l.split("\t")[0].strip() for l in titles_file.read_text(encoding="utf-8").splitlines() if l.strip()}
    skip = set()
    skip_file = HERE / "kg-more-skip.txt"
    old_index = {}
    if (OUT / "index.tsv").exists():
        for row in (OUT / "index.tsv").read_text(encoding="utf-8").splitlines():
            parts = row.split("\t")
            if len(parts) > 2:
                old_index[parts[0]] = parts[2]   # overview number -> title
    if skip_file.exists():
        for line in skip_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                skip.add(old_index.get(line.zfill(2), old_index.get(line, line)))
        # numbers only mean something against this overview: keep the titles, so the next run reads them right
        skip_file.write_text("".join(t + "\n" for t in sorted(skip) if t.startswith("File:")), encoding="utf-8")
    # the theme's own pictures: never offer the same shot again
    known_hashes = []
    for p in sorted((HERE / "kg-hd").glob("*.jpg")):
        try:
            known_hashes.append(dhash(Image.open(p)))
        except Exception:
            pass

    cache = OUT / "candidates.json"
    if "--reuse" in sys.argv and cache.exists():
        # The last search and its scores, kept: a skip only needs a new choice, not another half hour on Commons.
        scored = []
        for c in json.loads(cache.read_text(encoding="utf-8")):
            if c["title"] in used or c["title"] in skip:
                continue
            prev = OUT / "prev" / (hashlib.sha1(c["title"].encode()).hexdigest()[:12] + ".jpg")
            if not prev.exists():
                continue
            c["small"] = Image.open(prev).convert("RGB")
            scored.append(c)
        print(f"Reusing the last search: {len(scored)} candidates")
        return choose_and_fetch(scored)

    print("Looking on Wikimedia Commons...")
    found = collect()
    cands = []
    for title, (ii, why) in found.items():
        if title in used or title in skip or not wanted(title) or "jpeg" not in ii.get("mime", "image/jpeg"):
            continue
        if not big_enough(ii["width"], ii["height"]):
            continue
        cands.append((title, ii, why))
    print(f"  {len(cands)} pass the size and subject checks; scoring their previews...")

    scored = []
    for n, (title, ii, why) in enumerate(cands, 1):
        data = curl(ii.get("thumburl") or ii["url"], binary=True)
        if len(data) < 3000:
            continue
        try:
            im = Image.open(BytesIO(data))
            im.load()
        except Exception:
            continue
        colour, bright, sharp, small = measure(im)
        night = bright < 70
        if colour < 18 or sharp < 8:   # grey, washed out or soft: not the cheerful look
            continue
        score = colour + 0.6 * sharp - abs(bright - 125) * 0.25 + (12 if why == "seed" else 0)
        if night:
            score -= 8
        h = dhash(im)
        if any(bin(h ^ k).count("1") <= 8 for k in known_hashes):
            continue
        scored.append({"title": title, "ii": ii, "score": score, "night": night, "hash": h, "subject": subject(title),
                       "colour": colour, "bright": bright, "small": small})
        if n % 25 == 0:
            print(f"  {n}/{len(cands)}")
        time.sleep(0.2)

    (OUT / "prev").mkdir(exist_ok=True)
    keep_cache = []
    for c in scored:
        c["small"].save(OUT / "prev" / (hashlib.sha1(c["title"].encode()).hexdigest()[:12] + ".jpg"), quality=80)
        keep_cache.append({k: v for k, v in c.items() if k != "small"})
    cache.write_text(json.dumps(keep_cache, ensure_ascii=False), encoding="utf-8")
    return choose_and_fetch(scored)


def fetch(c):
    """One chosen photo at full size into work/kg-more; True when it is there. Tries the 1920 (or 3840) px copy, then the
    original. Runs in parallel with the others."""
    name = slug(c["title"])
    target = OUT / (name + ".jpg")
    if target.exists():
        try:
            with Image.open(target) as old:
                if old.height >= 1080 and old.width >= 1920:
                    return True
        except Exception:
            pass
        target.unlink()
    # Commons only serves thumbnails at its standard widths (1920, 3840...); another width is refused, which is
    # what made the old downloads fail and retry. 1920 is enough unless a panorama's 16:9 crop needs more.
    w, h = c["ii"]["width"], c["ii"]["height"]
    width = 1920 if w * 9 <= h * 16 or 1080 * w / h <= 1920 else 3840
    urls = []
    if w > width:
        q = api({"action": "query", "titles": c["title"], "prop": "imageinfo", "iiprop": "url", "iiurlwidth": width})
        try:
            urls.append(list(q["query"]["pages"].values())[0]["imageinfo"][0]["thumburl"])
        except Exception:
            pass
    urls.append(c["ii"]["url"])   # the original: always there, only bigger
    for url in urls:
        data = curl(url, binary=True, tries=2)
        if len(data) < 50_000:
            continue
        try:
            im = Image.open(BytesIO(data))
            if im.width > 4000:   # an original far bigger than needed: keep a 3840 copy, not 20 MB
                im = im.convert("RGB")
                im.thumbnail((3840, 3840), Image.LANCZOS)
                im.save(target, quality=92)
            else:
                target.write_bytes(data)
            return True
        except Exception:
            continue
    return False


def choose_and_fetch(scored):
    from concurrent.futures import ThreadPoolExecutor
    scored.sort(key=lambda c: -c["score"])
    picked, per_subject, nights = [], {}, 0
    for c in scored:   # in score order, with some to spare for downloads that fail
        if len(picked) >= WANT + 20:
            break
        if per_subject.get(c["subject"], 0) >= PER_SUBJECT:
            continue
        if c["night"] and nights >= MAX_NIGHT:
            continue
        if any(bin(c["hash"] ^ k["hash"]).count("1") <= 10 for k in picked):
            continue
        picked.append(c)
        per_subject[c["subject"]] = per_subject.get(c["subject"], 0) + 1
        nights += c["night"]
    print(f"Downloading the best {len(picked)} (six at a time)...")
    with ThreadPoolExecutor(6) as pool:
        ok = list(pool.map(fetch, picked))
    chosen = [c for c, good in zip(picked, ok) if good][:WANT]
    failed = [c["title"] for c, good in zip(picked, ok) if not good]
    if failed:   # never offered again
        with open(HERE / "kg-more-skip.txt", "a", encoding="utf-8") as f:
            f.writelines(t + "\n" for t in failed)
        print(f"  {len(failed)} would not download; others took their place")
    keep = set()
    rows = []
    for c in chosen:
        name = slug(c["title"])
        keep.add(name + ".jpg")
        meta = c["ii"].get("extmetadata", {})
        artist = re.sub(r"\s+", " ", re.sub("<[^>]+>", "", meta.get("Artist", {}).get("value", ""))).strip()
        licence = meta.get("LicenseShortName", {}).get("value", "?")
        rows.append([f"{len(rows) + 1:02d}", name, c["title"], artist, licence, c["ii"]["descriptionurl"],
                     "night" if c["night"] else "day", caption(c["title"]), f"{c['colour']:.0f}", f"{c['bright']:.0f}"])
    for old in OUT.glob("nova-*.jpg"):   # pictures a skip replaced
        if old.name not in keep:
            old.unlink()
    (OUT / "index.tsv").write_text("\n".join("\t".join(r) for r in rows) + "\n", encoding="utf-8")

    # the overview: number, caption, day or night
    W, H, cols = 384, 216, 5
    sheet = Image.new("RGB", (W * cols, (H + 24) * ((len(rows) + cols - 1) // cols)), "black")
    d = ImageDraw.Draw(sheet)
    by_title = {c["title"]: c for c in chosen}
    for k, r in enumerate(rows):
        x, y = (k % cols) * W, (k // cols) * (H + 24)
        sheet.paste(by_title[r[2]]["small"].resize((W, H)), (x, y))
        d.rectangle((x, y, x + 34, y + 20), fill="black")
        d.text((x + 6, y + 4), r[0], fill="white")
        d.text((x + 6, y + H + 5), f"{r[6]}  {r[2][5:60]}", fill="#bbbbbb")
    sheet.save(HERE / "kragujevac-nove.jpg", quality=85)
    print(f"Done: {len(rows)} pictures in work/kg-more, overview work/kragujevac-nove.jpg")
    if len(rows) < WANT:
        print(f"Only {len(rows)} passed every check. Add Commons titles to SEEDS or a category to ROOT_CATEGORIES, "
              "and run again.")


if __name__ == "__main__":
    sys.exit(main())
