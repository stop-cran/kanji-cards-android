#!/usr/bin/env python3
"""Generate the bundled reference paths for the app's built-in kana cards."""

import json
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from svgpathtools import parse_path

REVISION = "70a0b7ae0c18ceb5cb358274b029cce0234a43bc"
URL = f"https://raw.githubusercontent.com/KanjiVG/kanjivg/{REVISION}/kanji/{{code}}.svg"
NAMESPACE = "{http://www.w3.org/2000/svg}"
KVG_NAMESPACE = "{https://kanjivg.tagaini.net/}"
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "kana-strokes.json"
HIRAGANA = (
    "あいうえおかきくけこさしすせそたちつてとなにぬねの"
    "はひふへほまみむめもやゆよらりるれろわをん"
    "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゃゅょ"
)


def resample(path, count=16):
    fine = [path.point(i / 400) for i in range(401)]
    lengths = [0.0]
    for start, end in zip(fine, fine[1:]):
        lengths.append(lengths[-1] + abs(end - start))
    points = []
    segment = 0
    for index in range(count):
        target = lengths[-1] * index / (count - 1)
        while segment < len(lengths) - 2 and lengths[segment + 1] < target:
            segment += 1
        span = lengths[segment + 1] - lengths[segment]
        fraction = 0.0 if span == 0 else (target - lengths[segment]) / span
        point = fine[segment] + (fine[segment + 1] - fine[segment]) * fraction
        points.append([round(point.real, 1), round(point.imag, 1)])
    return points


def fetch(char):
    url = URL.format(code=f"{ord(char):05x}")
    with urllib.request.urlopen(url, timeout=30) as response:
        root = ET.fromstring(response.read())
    strokes = []
    for element in root.iter(f"{NAMESPACE}path"):
        strokes.append({
            "id": len(strokes) + 1,
            "type": element.get(f"{KVG_NAMESPACE}type", ""),
            "points": resample(parse_path(element.get("d"))),
        })
    return char, {
        "schemaVersion": 1,
        "kanji": char,
        "source": "KanjiVG (CC BY-SA 3.0, https://kanjivg.tagaini.net)",
        "sourceRevision": REVISION,
        "viewBox": [0, 0, 109, 109],
        "strokeCount": len(strokes),
        "strokes": strokes,
    }


def main():
    chars = sorted(set(HIRAGANA + "".join(chr(ord(c) + 0x60) for c in HIRAGANA)))
    with ThreadPoolExecutor(max_workers=4) as pool:
        data = dict(pool.map(fetch, chars))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Wrote {len(data)} kana references to {OUTPUT}")


if __name__ == "__main__":
    main()
