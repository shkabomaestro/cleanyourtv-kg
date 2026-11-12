"""Builds the Kragujevac theme's wallpapers from the photos in work/kg-hd/ (numbers from kg-hd/index.tsv).

    tools\\venv\\Scripts\\python.exe -I work\\kg_grade.py

Each picture is cropped to 16:9, scaled to 1920x1080, graded for the time of day it is shown at (a fresh
morning, a warm afternoon, a night) with a vivid look, and shaded at the top and bottom so the clock and
the dock stay readable. The stick draws its UI at 1280x720 (wm size; HDMI output is 1080p) and the launcher
scales the picture down to that, so 1920x1080 is the owner's choice of file size, not what reaches the panel.
"dusk" is a day photo turned into a night one, used where no free night photo exists (the school, the car);
the V3 monument has real night photos. Writes themes/kragujevac/wallpapers/*.jpg, captions.txt, CREDITS.md, the wallpaper_moods
block of config.json, and work/kragujevac-opcije.jpg (the overview).
"""
import csv
import io
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "work" / "kg-hd"
THEME = ROOT / "themes" / "kragujevac"
OUT = THEME / "wallpapers"
SIZE = (1920, 1080)
MAX_BYTES = 1500 * 1024

# name, source number, look, vertical crop anchor (0 top .. 1 bottom), caption
SHOTS = [
    ("gimnazija-jutro", "001", "morning", 0.30, "Прва крагујевачка гимназија"),
    ("gimnazija-dan", "002", "evening", 0.55, "Прва крагујевачка гимназија"),
    ("gimnazija-noc", "001", "dusk", 0.30, "Прва крагујевачка гимназија"),
    ("v3-jutro", "003", "morning", 0.50, "Шумарице · Споменик стрељаним ђацима и професорима"),
    ("v3-dan", "004", "evening", 0.45, "Шумарице · Споменик стрељаним ђацима и професорима"),
    ("v3-noc", "006", "night", 0.50, "Шумарице · Споменик стрељаним ђацима и професорима"),
    ("sumarice-jutro", "008", "morning", 0.55, "Спомен-парк „Крагујевачки октобар“"),
    ("sumarice-dan", "010", "evening", 0.60, "Спомен-парк „Крагујевачки октобар“"),
    ("sumarice-noc", "007", "night", 0.50, "Спомен-парк „Крагујевачки октобар“"),
    ("sud-jutro", "011", "morning", 0.40, "Зграда суда · Трг војводе Радомира Путника"),
    ("sud-dan", "013", "evening", 0.45, "Зграда суда"),
    ("zastava-jutro", "014", "morning", 0.40, "Застава · управна зграда са старим сатом"),
    ("zastava-dan", "015", "evening", 0.35, "Застава · управна зграда"),
    ("fica-jutro", "016", "morning", 0.50, "Застава 750 „Фића“"),
    ("fica-dan", "016", "evening", 0.50, "Застава 750 „Фића“"),
    ("fica-noc", "016", "dusk", 0.50, "Застава 750 „Фића“"),
]
MOODS = {"morning": "morning", "evening": "evening", "night": "night", "dusk": "night"}


def crop_16_9(im, anchor):
    w, h = im.size
    if w * 9 >= h * 16:
        nw = h * 16 // 9
        left = (w - nw) // 2
        return im.crop((left, 0, left + nw, h))
    nh = w * 9 // 16
    top = int((h - nh) * anchor)
    return im.crop((0, top, w, top + nh))


def channels(im, r, g, b):
    """Multiply the three channels: the colour temperature of the light."""
    lut = lambda k: [min(255, int(v * k + 0.5)) for v in range(256)]
    return im.point(lut(r) + lut(g) + lut(b))


def curve(im, gamma):
    """Gamma on all channels: below 1 lifts the midtones, above 1 deepens them."""
    table = [int(255 * (v / 255) ** gamma + 0.5) for v in range(256)]
    return im.point(table * 3)


def clarity(im, percent):
    """Local contrast, what makes stone and foliage look close enough to touch."""
    return im.filter(ImageFilter.UnsharpMask(radius=28, percent=percent, threshold=0))


def vivid(im, colour, contrast):
    im = ImageOps.autocontrast(im, cutoff=(0.3, 0.3), preserve_tone=True)
    im = ImageEnhance.Color(im).enhance(colour)
    return ImageEnhance.Contrast(im).enhance(contrast)


def shade(im, top, bottom, top_until=0.30, bottom_from=0.50):
    """Black fading in toward the top edge (greeting, clock) and the bottom edge (dock)."""
    w, h = im.size
    column = []
    for y in range(h):
        t = y / (h - 1)
        a = 0.0
        if t < top_until:
            a = top * (1 - t / top_until) ** 1.5
        if t > bottom_from:
            a = max(a, bottom * ((t - bottom_from) / (1 - bottom_from)) ** 1.3)
        column.append(int(255 * a))
    mask = Image.new("L", (1, h))
    mask.putdata(column)
    return Image.composite(Image.new("RGB", im.size, "black"), im, mask.resize((w, h)))


def vignette(im, strength):
    w, h = im.size
    mask = Image.radial_gradient("L").resize((w, h))           # 0 at the centre, 255 at the corners
    mask = mask.point(lambda v: int(strength * 255 * (v / 255) ** 2.2))
    return Image.composite(Image.new("RGB", im.size, "black"), im, mask)


def grade(im, look):
    if look == "morning":      # clear, cool, crisp light
        im = vivid(im, 1.30, 1.08)
        im = channels(curve(im, 0.94), 0.985, 1.0, 1.035)
        im = clarity(im, 22)
        return shade(im, 0.30, 0.52)
    if look == "evening":      # low warm sun
        im = vivid(im, 1.34, 1.10)
        im = channels(curve(im, 1.03), 1.075, 1.0, 0.86)
        im = clarity(im, 22)
        return shade(vignette(im, 0.22), 0.32, 0.55)
    if look == "night":        # a real night photo: keep it, only cleaner and deeper
        im = vivid(im, 1.15, 1.06)
        im = clarity(im, 10)
        return shade(im, 0.18, 0.42)
    assert look == "dusk", look   # a day photo turned to night: darker, blue, the sky deepest at the top
    im = vivid(im, 1.10, 1.12)
    im = ImageEnhance.Color(im).enhance(0.72)
    im = curve(im, 1.55)
    im = channels(im, 0.50, 0.66, 1.00)
    im = ImageEnhance.Brightness(im).enhance(0.92)
    im = clarity(im, 16)
    return shade(vignette(im, 0.45), 0.55, 0.50, top_until=0.55)


def save(im, path):
    quality = 97
    while True:
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=quality, optimize=True, progressive=False, subsampling=0)   # 4:4:4, no colour smearing
        if buf.tell() <= MAX_BYTES or quality <= 70:
            path.write_bytes(buf.getvalue())
            return quality, buf.tell()
        quality -= 3


def main():
    index = {r[0]: r for r in csv.reader((SRC / "index.tsv").open(encoding="utf-8"), delimiter="\t")}
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.jpg"):
        old.unlink()
    moods = {"morning": [], "evening": [], "night": []}
    captions, credits, done = [], [], []
    for name, num, look, anchor, caption in SHOTS:
        im = Image.open(SRC / f"{num}.jpg").convert("RGB")
        im = crop_16_9(im, anchor).resize(SIZE, Image.LANCZOS)
        im = grade(im, look).filter(ImageFilter.UnsharpMask(radius=0.8, percent=40, threshold=2))
        quality, size = save(im, OUT / f"{name}.jpg")
        moods[MOODS[look]].append(f"{name}.jpg")
        captions.append(f"{name}.jpg\t{caption}")
        _, title, artist, licence, url = index[num][:5]
        note = "day photo graded to night" if look == "dusk" else "colour graded"
        credits.append(f"| {name}.jpg | [{title[5:]}]({url}) | {artist} | {licence} | {note} |")
        done.append((name, im))
        print(f"{name}.jpg  {size // 1024} KB  q{quality}  ({look})")
    (OUT / "captions.txt").write_text("\n".join(captions) + "\n", encoding="utf-8")
    (THEME / "CREDITS.md").write_text("\n".join([
        "# Credits", "",
        "Photos from Wikimedia Commons, cropped to 16:9, scaled to 1920x1080, colour graded and shaded at the top and",
        "bottom by `work/kg_grade.py`. The modified pictures are under the same licence as their originals.", "",
        "| File | Original | Author | Licence | Change |", "|---|---|---|---|---|", *credits]) + "\n", encoding="utf-8")
    cfg_path = THEME / "config.json"
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    cfg["wallpaper_moods"] = moods
    cfg_path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    W, H, cols = 640, 360, 3
    sheet = Image.new("RGB", (W * cols, H * ((len(done) + cols - 1) // cols)), "black")
    d = ImageDraw.Draw(sheet)
    for i, (name, im) in enumerate(done):
        x, y = (i % cols) * W, (i // cols) * H
        sheet.paste(im.resize((W, H), Image.LANCZOS), (x, y))
        d.rectangle((x, y, x + 150, y + 26), fill="black")
        d.text((x + 8, y + 7), name, fill="white")
    sheet.save(ROOT / "work" / "kragujevac-opcije.jpg", quality=88)


if __name__ == "__main__":
    main()
