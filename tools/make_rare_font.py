"""Builds app/src/main/res/font/rare_han_a.otf (BMP) and rare_han_b.otf (plane 2): tiny subsets of Hanazono Mincho (HanaMinA/HanaMinB) with exactly the rare CJK
characters that the content articles use and that phone system fonts usually cannot draw.

Usage: python tools/make_rare_font.py [content-repo-dir] [dir-with-HanaMinA.otf-and-HanaMinB.otf]
Fonts: https://github.com/cjkvi/HanaMinAFDKO/releases (Hanazono Font License / SIL OFL 1.1; see third_party/fonts/).
Needs: pip install fonttools
"""
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

# Must stay in sync with RareHan.isRare in the app.
RANGES = [(0x2E80, 0x2FDF), (0x3400, 0x4DBF), (0xF900, 0xFAFF), (0x20000, 0x2FFFF)]
FAMILY = "Kanji Rare Subset"


def is_rare(cp: int) -> bool:
    return any(a <= cp <= b for a, b in RANGES)


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    content = Path(sys.argv[1]) if len(sys.argv) > 1 else root.parent / "learning-japanese"
    fonts = Path(sys.argv[2]) if len(sys.argv) > 2 else Path.cwd()
    chars = set()
    for folder in ("kanji", "words", "articles"):
        for f in (content / folder).glob("*.md"):
            chars |= {c for c in f.read_text(encoding="utf-8") if is_rare(ord(c))}
    print("rare characters:", " ".join(f"U+{ord(c):04X}" for c in sorted(chars)))

    build = root / "build" / "rare"
    build.mkdir(parents=True, exist_ok=True)
    parts = []
    missing = set(chars)
    for name in ("HanaMinA.otf", "HanaMinB.otf"):
        font = TTFont(fonts / name)
        cmap = font.getBestCmap()
        have = {c for c in missing if ord(c) in cmap}
        if not have:
            continue
        opts = subset.Options()
        opts.layout_features = []
        opts.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
        sub = subset.Subsetter(opts)
        sub.populate(text="".join(have))
        sub.subset(font)
        missing -= have
        path = build / name
        font.save(path)
        parts.append(str(path))
    if missing:
        sys.exit("no glyph in HanaMin for: " + " ".join(f"U+{ord(c):04X}" for c in sorted(missing)))
    outdir = root / "app" / "src" / "main" / "res" / "font"
    outdir.mkdir(parents=True, exist_ok=True)
    for part, res in zip(parts, ("rare_han_a.otf", "rare_han_b.otf")):
        font = TTFont(part)
        for rec in font["name"].names:
            if rec.nameID in (1, 4, 16):
                rec.string = FAMILY
            elif rec.nameID == 6:
                rec.string = FAMILY.replace(" ", "")
        out = outdir / res
        font.save(out)
        print(f"wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()

