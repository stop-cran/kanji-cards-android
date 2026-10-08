# Kanji Cards (Android)

A personal kanji-learning app. Cards, words and articles live in a separate Git repo
(default: <https://github.com/stop-cran/learning-japanese>); the app syncs them and schedules
reviews with FSRS.

- **Quiz mode** – pick the meaning of a displayed kanji.
- **Drawing mode** – draw the kanji from its caption; stroke order/direction are checked.
- **Articles** – Markdown with in-app navigation between kanji, words and articles.
- **Configurable content repo** – point the app at your own fork in Settings.

Status: content sync (settings behind ⚙, safe zip import, Room storage, WorkManager), FSRS-5 and the meaning quiz are in place; drawing mode
and the article viewer are next. See [docs/PLAN.md](docs/PLAN.md) and [docs/CONTENT-SCHEMA.md](docs/CONTENT-SCHEMA.md).

## License

Code: Apache-2.0 (see [LICENSE](LICENSE)). Content repos have their own licenses; data derived from
KANJIDIC2, KanjiVG and JMdict is CC BY-SA and requires attribution (shown in the app's About screen).

## Fonts
The quiz kanji uses bundled OFL fonts, subset to JIS X 0208 level 1 + ASCII (about 6 MB total, `app/src/main/res/font/kanji_*.ttf`):
Noto Serif JP (Mincho, wght 400), Klee One (textbook), Yuji Syuku (brush). Licences are in `third_party/fonts/`.
The plain face is the system CJK font. Which faces a card may use depends on its FSRS stability (`core/srs/FontPolicy`); a lapse resets stability, so the card drops back to plainer faces.
To regenerate a subset: `python -m fontTools.subset <font> --text-file=chars.txt --layout-features='' --no-hinting` (`pip install fonttools brotli`).
