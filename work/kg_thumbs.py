"""Fetch thumbnails of Commons files and lay them out on numbered contact sheets.

    python -I work/kg_thumbs.py <titles.txt> <out dir> <thumb width>

titles.txt has one "File:..." title per line (anything after a TAB is ignored). Writes NNN.jpg into the
out dir, index.tsv (number, title, author, licence, page URL) and sheet-N.jpg next to it."""
import json
import re
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

from PIL import Image, ImageDraw

API = "https://commons.wikimedia.org/w/api.php"
UA = "cleanyourtv-wallpaper-search/1.0"
titles_file, out, width = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)


def curl(url):
    for attempt in range(4):
        try:
            data = subprocess.run(["curl", "-sSL", "--fail", "-A", UA, url], check=True, capture_output=True, timeout=120).stdout
            if data:
                return data
        except Exception:
            pass
        time.sleep(4 * (attempt + 1))
    return b""


titles = [l.split("\t")[0].strip() for l in titles_file.read_text(encoding="utf-8").splitlines() if l.strip()]
index = []
for i, title in enumerate(titles, 1):
    name = out / f"{i:03d}.jpg"
    q = urllib.parse.urlencode({"action": "query", "titles": title, "prop": "imageinfo", "format": "json",
                                "iiprop": "url|size|extmetadata", "iiurlwidth": width,
                                "iiextmetadatafilter": "LicenseShortName|Artist"})
    raw = curl(f"{API}?{q}")
    try:
        ii = list(json.loads(raw)["query"]["pages"].values())[0]["imageinfo"][0]
    except Exception:
        continue
    meta = ii.get("extmetadata", {})
    artist = re.sub(r"\s+", " ", re.sub("<[^>]+>", "", meta.get("Artist", {}).get("value", ""))).strip()
    if not name.exists() or name.stat().st_size < 3000:
        data = curl(ii["thumburl"])
        if len(data) < 3000:
            continue
        name.write_bytes(data)
        time.sleep(0.6)
    index.append((f"{i:03d}", title, artist, meta.get("LicenseShortName", {}).get("value", "?"), ii["descriptionurl"],
                  f"{ii['width']}x{ii['height']}"))
(out / "index.tsv").write_text("\n".join("\t".join(r) for r in index) + "\n", encoding="utf-8")

if width <= 640:   # contact sheets only make sense for small thumbnails
    W, H, cols, per = 320, 180, 6, 36
    for s in range(0, len(index), per):
        chunk = index[s:s + per]
        sheet = Image.new("RGB", (W * cols, H * ((len(chunk) + cols - 1) // cols)), "black")
        d = ImageDraw.Draw(sheet)
        for k, row in enumerate(chunk):
            im = Image.open(out / f"{row[0]}.jpg").convert("RGB")
            w, h = im.size
            if w / h > 16 / 9:
                nw = int(h * 16 / 9); im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
            else:
                nh = int(w * 9 / 16); im = im.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
            x, y = (k % cols) * W, (k // cols) * H
            sheet.paste(im.resize((W, H)), (x, y))
            d.rectangle((x, y, x + 40, y + 22), fill="black"); d.text((x + 6, y + 5), row[0], fill="white")
        sheet.save(out / f"sheet-{s // per + 1}.jpg", quality=82)
print(len(index), "thumbnails")
