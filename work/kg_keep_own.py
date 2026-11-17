"""Keeps the owner's own pictures (moja-*.jpg) when the theme is rebuilt on a computer that does not have them.

    tools\\venv\\Scripts\\python.exe -I work\\kg_keep_own.py

work\\kg-update.ps1 first copies the moja-*.jpg already on the stick into work\\kg-from-tv\\. After kg_grade.py, this
puts every one the theme now lacks back into themes\\kragujevac\\wallpapers\\, with its caption and in the morning
and afternoon groups of config.json, so pushing the theme does not drop them. Already graded: copied as they are.
"""
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FROM_TV = ROOT / "work" / "kg-from-tv"
THEME = ROOT / "themes" / "kragujevac"
WALL = THEME / "wallpapers"

sys.path.insert(0, str(ROOT / "work"))
from kg_grade import SHOTS  # noqa: E402  (the captions of the owner's pictures are listed there)

CAPTIONS = {name + ".jpg": caption for name, _, _, _, caption in SHOTS}


def main():
    found = sorted(FROM_TV.glob("moja-*.jpg")) if FROM_TV.exists() else []
    if not found:
        print("no own pictures to keep")
        return
    cfg_path = THEME / "config.json"
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    moods = cfg.setdefault("wallpaper_moods", {"morning": [], "evening": [], "night": []})
    captions_path = WALL / "captions.txt"
    lines = captions_path.read_text(encoding="utf-8").splitlines() if captions_path.exists() else []
    named = {line.split("\t")[0] for line in lines}
    kept = 0
    for picture in found:
        if (WALL / picture.name).exists():
            continue   # this computer has the original and kg_grade.py made it
        shutil.copy2(picture, WALL / picture.name)
        for group in ("morning", "evening"):
            if picture.name not in moods.setdefault(group, []):
                moods[group].append(picture.name)
        if picture.name not in named:
            lines.append(f"{picture.name}\t{CAPTIONS.get(picture.name, 'Крагујевац')}")
        kept += 1
    cfg_path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    captions_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"kept {kept} own picture(s) from the stick")


if __name__ == "__main__":
    main()
