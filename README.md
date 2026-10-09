# Kanji Cards (Android)

A personal kanji-learning app. Cards, words and articles live in a separate Git repo
(default: <https://github.com/stop-cran/learning-japanese>); the app syncs them and schedules
reviews with FSRS.

- **Quiz mode** – pick the meaning of a displayed kanji.
- **Drawing mode** – draw the kanji from its caption; stroke order/direction are checked.
- **Articles** – Markdown with in-app navigation between kanji, words and articles.
- **Configurable content repo** – point the app at your own fork in Settings; it syncs on launch (only when the branch head changed) and daily in the background.
- **Stacks** – filter by tag (Starter, JLPT N5…N1); every stack keeps its own FSRS schedule.
- **Word quizzes** – explicit vocabulary JLPT levels include kana-only words in N5/N4; unlabelled words retain the hardest-kanji fallback. N4 words include N5, with separate schedules for each stack and quiz direction.
- **Search** in Browse cards: kanji, meaning, kana (katakana prefers on'yomi, hiragana kun'yomi) and romaji.
- **Gentle reminder** (optional) that backs off while ignored; **brushes**, stroke-number hints and an opt-in drawing log with zip export.

Status: working on an emulator; preparing for a real-device trial and Google Play. See [docs/PLAN.md](docs/PLAN.md),
[docs/CONTENT-SCHEMA.md](docs/CONTENT-SCHEMA.md), [docs/PRIVACY.md](docs/PRIVACY.md) and [docs/RELEASE.md](docs/RELEASE.md).

## License

Code: Apache-2.0 (see [LICENSE](LICENSE)). Content repos have their own licenses; data derived from
KANJIDIC2, KanjiVG and JMdict is CC BY-SA and requires attribution (shown in the app's About screen).

## Fonts
The quiz kanji uses bundled OFL fonts, subset to JIS X 0208 level 1 + ASCII (about 6 MB total, `app/src/main/res/font/kanji_*.ttf`):
Noto Serif JP (Mincho, wght 400), Klee One (textbook), Yuji Syuku (brush). Licences are in `third_party/fonts/`.
The plain face is the system CJK font. Which faces a card may use depends on its FSRS stability (`core/srs/FontPolicy`); a lapse resets stability, so the card drops back to plainer faces.
To regenerate a subset: `python -m fontTools.subset <font> --text-file=chars.txt --layout-features='' --no-hinting` (`pip install fonttools brotli`).
