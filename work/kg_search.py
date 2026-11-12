"""Search Wikimedia Commons (text queries and categories) for landscape photos and write the hits to
kg-search.tsv: width, height, licence, author, title. Run: python -I work/kg_search.py"""
import json
import re
import subprocess
import time
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
API = "https://commons.wikimedia.org/w/api.php"
UA = "cleanyourtv-wallpaper-search/1.0"

QUERIES = [
    "Kragujevac sud", "Zgrada suda Kragujevac", "Okružni sud Kragujevac", "Palata pravde Kragujevac",
    "Зграда суда Крагујевац", "Kragujevac trg", "Трг Крагујевац", "Kragujevac Nova godina", "Kragujevac noću",
    "Kragujevac night", "Kragujevac noć", "Крагујевац ноћу", "Šumarice aerial", "Šumarice iz vazduha",
    "Spomen-park Kragujevački oktobar", "Šumarice dron", "Gimnazija Kragujevac noć", "Kragujevac praznici",
    "Kragujevac Christmas", "Zastava Kragujevac zgrada", "Stara zgrada Zastave Kragujevac", "Kragujevac drone",
    "Kragujevac iz vazduha", "Kragujevac centar", "Крагујевац центар града", "Kragujevac city center night",
]
CATEGORIES = [
    "Šumarice Memorial Park", "First Kragujevac Gymnasium", "Kragujevac at night", "Court building in Kragujevac",
    "Kragujevac", "Buildings in Kragujevac", "Aerial photographs of Serbia", "Zastava 750",
    "Monument to the executed pupils and teachers", "Squares in Kragujevac", "Streets in Kragujevac",
    "Christmas in Serbia", "Night in Serbia", "Vojno-tehnički zavod u Kragujevcu",
]


def get(params):
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            out = subprocess.run(["curl", "-sSL", "--fail", "-A", UA, url], check=True, capture_output=True, timeout=90).stdout
            return json.loads(out)
        except Exception:
            if attempt == 3:
                return {}
            time.sleep(4 * (attempt + 1))


def rows(data, source):
    for p in sorted(data.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0)):
        if "imageinfo" not in p:
            continue
        ii = p["imageinfo"][0]
        if ii["width"] < 1900 or ii["width"] < ii["height"] * 1.2:
            continue
        m = ii.get("extmetadata", {})
        artist = re.sub(r"\s+", " ", re.sub("<[^>]+>", "", m.get("Artist", {}).get("value", ""))).strip()[:40]
        yield (p["title"], f"{ii['width']}x{ii['height']}", m.get("LicenseShortName", {}).get("value", "?"), artist, source)


common = {"action": "query", "format": "json", "prop": "imageinfo", "iiprop": "size|extmetadata",
          "iiextmetadatafilter": "LicenseShortName|Artist"}
seen, out = set(), []
for q in QUERIES:
    data = get({**common, "generator": "search", "gsrsearch": q + " filetype:bitmap", "gsrnamespace": 6, "gsrlimit": 20})
    for r in rows(data, "q:" + q):
        if r[0] not in seen:
            seen.add(r[0]); out.append(r)
    time.sleep(1)
for c in CATEGORIES:
    data = get({**common, "generator": "categorymembers", "gcmtitle": "Category:" + c, "gcmtype": "file", "gcmlimit": 200})
    for r in rows(data, "c:" + c):
        if r[0] not in seen:
            seen.add(r[0]); out.append(r)
    time.sleep(1)
(HERE / "kg-search.tsv").write_text("\n".join("\t".join(r) for r in out) + "\n", encoding="utf-8")
print(len(out), "hits")
