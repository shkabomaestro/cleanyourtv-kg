"""Rebuilds the bundle for sharing (share/cleanyourtv-kragujevac and its zip) from this working copy.

    tools\\venv\\Scripts\\python.exe -I work\\make_share.py

Copies what was changed or added over the upstream repo, without what is personal: the greeting name, the TV's
address, the music, backups and screenshots. Stops if one of those strings is still found in the bundle.
"""
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "share" / "cleanyourtv-kragujevac"
OVERLAY = BUNDLE / "overlay"
WORK = ["kg_grade.py", "kg_thumbs.py", "kg_search.py", "make_nav_sounds.py", "make_share.py", "kg-hd-titles.txt", "README.md",
        "restore-all.ps1", "b1-telemetry.txt", "b2-content.txt", "b3-apps.txt", "b4-xiaomi.txt", "b5-plumbing.txt",
        "b6-screensaver.txt", "debloat-all.txt"]
PERSONAL = re.compile(r"Бошко|Boško|БОШКО|shkab|Shkabo|10\.144\.|gmail|3c:2c|de:39")


def git(*args):
    return subprocess.run(["git", "-c", "core.quotepath=false", *args], cwd=ROOT, check=True, capture_output=True,
                          text=True, encoding="utf-8").stdout.splitlines()


def scrub(text):
    return (text.replace('"greeting_name": "Бошко"', '"greeting_name": ""')
            .replace('translatable="false">Бошко</string>', 'translatable="false"></string>')
            .replace("10.144.180.61", "<tv ip>").replace("БОШКО", "ИМЕ"))


def main():
    prompt = (BUNDLE / "PROMPT-SETUP.md").read_text(encoding="utf-8")
    shutil.rmtree(OVERLAY, ignore_errors=True)
    files = [f for f in git("ls-files", "-m") if f != ".gitignore"]
    files += [f for f in git("ls-files", "-o", "--exclude-standard")
              if re.match(r"(launcher/|addons/radio/|themes/kragujevac/)", f) and not f.startswith("themes/kragujevac/music/")]
    files += ["work/" + name for name in WORK]
    for name in files:
        src, dst = ROOT / name, OVERLAY / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        if src.suffix in (".json", ".xml", ".md", ".ps1"):
            dst.write_text(scrub(src.read_text(encoding="utf-8")), encoding="utf-8", newline="")
        else:
            shutil.copyfile(src, dst)
    music = OVERLAY / "themes" / "kragujevac" / "music"
    music.mkdir(parents=True)
    (music / "README.txt").write_text("Ovde stavi svoje audio fajlove (MP3, M4A, OGG, FLAC, WAV). Sviraju po abecednom redu imena, ukrug.\n"
                                      "Pesme se ne dele uz paket: svako stavlja svoje.\n", encoding="utf-8")
    (BUNDLE / "PROMPT-SETUP.md").write_text(prompt, encoding="utf-8", newline="")

    for f in BUNDLE.rglob("*"):
        if f.is_file() and f.suffix in (".md", ".json", ".xml", ".ps1", ".txt", ".java", ".py", ".gradle"):
            found = PERSONAL.search(f.read_text(encoding="utf-8", errors="replace"))
            if found and f.name != "make_share.py":   # this file names the strings it looks for
                raise SystemExit(f"personal data left in {f.relative_to(BUNDLE)}: {found.group(0)}")
    archive = BUNDLE.parent / "cleanyourtv-kragujevac-setup.zip"
    archive.unlink(missing_ok=True)
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for f in sorted(BUNDLE.rglob("*")):
            if f.is_file():
                z.write(f, f.relative_to(BUNDLE.parent).as_posix())
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        print(len(z.namelist()), "files,", archive.stat().st_size // (1024 * 1024), "MB")


if __name__ == "__main__":
    main()
