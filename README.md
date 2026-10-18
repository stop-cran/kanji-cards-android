# Ensō Nihongo (Android)

A personal kanji-learning app. Cards, words and articles live in a separate Git repo
(default: <https://github.com/stop-cran/learning-japanese>); the app syncs them and schedules
reviews with FSRS.

- **Quiz mode** – pick the meaning of a displayed kanji.
- **Drawing mode** – draw the kanji from its caption; stroke order/direction are checked.
- **Kana section** – study hiragana and katakana, including voiced forms and common combinations (unlocked separately per mode once both component kana reach 7 days of FSRS stability), with two-way quizzes, typing and stroke-checked drawing.
- **Articles** – Markdown with in-app navigation between kanji, words and articles.
- **Configurable content repo** – point the app at your own fork in Settings; it syncs on launch (only when the branch head changed) and daily in the background.
- **Stacks** – filter by tag (Starter, JLPT N5…N1); every stack keeps its own FSRS schedule.
- **Word quizzes** – explicit vocabulary JLPT levels include kana-only words in N5/N4; unlabelled words retain the hardest-kanji fallback. N4 words include N5, with separate schedules for each stack and quiz direction.
- **Grammar pilot** – contextual contrasts with alternating choice and typing questions, full English meaning, explanatory feedback and linked articles. Both presentations share one schedule per exercise; suitable alternative completions are accepted.
- **Search** in Browse cards: kanji, meaning, kana (katakana prefers on'yomi, hiragana kun'yomi) and romaji.
- **Gentle reminder** (optional) that backs off while ignored; **brushes**, stroke-number hints and an opt-in drawing log with zip export.

Status: working on an emulator; preparing for a real-device trial and Google Play. See [docs/DESIGN.md](docs/DESIGN.md), [docs/PLAN.md](docs/PLAN.md),
[docs/CONTENT-SCHEMA.md](docs/CONTENT-SCHEMA.md), [docs/PRIVACY.md](docs/PRIVACY.md) and [docs/RELEASE.md](docs/RELEASE.md).

## Build and test

Requirements: JDK 17 (`JAVA_HOME`), the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`) with platform 36. Gradle comes from the wrapper.

```
./gradlew testDebugUnitTest      # JVM tests: parser, FSRS, matcher, sync (fake HTTP), Room migrations and view models and Compose UI tests on Robolectric
./gradlew lintDebug              # Android lint; must be clean of errors
./gradlew detekt                 # static analysis; existing findings are in config/detekt/detekt-baseline.xml, new code must add none
./gradlew installDebug           # build and install on a connected device/emulator (API 26+)
./gradlew assembleRelease        # R8-minified build; signing is optional, see docs/RELEASE.md
```

Some tests read the content repo when it is cloned next to this one (`../learning-japanese`, relative to `app/`) and skip otherwise;
CI clones it. After editing that clone, re-run with `./gradlew cleanTestDebugUnitTest testDebugUnitTest`, because Gradle does not see
the sibling directory as an input. On Windows use `gradlew.bat`.

Code layout and the reasons behind it: [docs/DESIGN.md](docs/DESIGN.md). Contributor notes: [.github/copilot-instructions.md](.github/copilot-instructions.md).

## Grammar pilot and local progress analysis

The bounded formal-noun corpus has **401 reviewed exercises in 32 sets**, including five mixed sets, published in
[learning-japanese at c2a9844](https://github.com/stop-cran/learning-japanese/commit/c2a98448ca7f7c5f77c199743b3d5ec21d851c97).
This is scoped practice, not exhaustive Japanese grammar coverage. New exercises are sampled randomly within the shared daily allowance;
relationships do not yet prioritize introductions ([future work #48](https://github.com/stop-cran/kanji-cards-android/issues/48)).

**Settings → Export progress** saves review states, the canonical answer log, settings and linked Grammar response details as a ZIP.
Recorded typed responses are stored automatically on the device; the separate drawing log remains opt-in. Exports contain private study
history and typed text: do not commit or upload them. See [privacy](docs/PRIVACY.md) and the [export contract](docs/CONTENT-SCHEMA.md#progress-export-v2).

The dependency-free Python 3.9+ analyzer reads a local ZIP without extracting it or using the network:

```powershell
python -B tools\analyze_grammar_progress.py "C:\private\kanji-progress.zip" > "C:\private\grammar-report.json"
python -B -m unittest discover -s tools -p test_analyze_grammar_progress.py
```

The JSON report omits response text and general settings. It gives sample sizes/errors by recorded construction and relationship IDs,
split by choice/typing and first-observed/repeated exercise attempts. Those views reuse the same events, not independent samples.
Empty cells have `correct_rate: null`; legacy exports explicitly have no Grammar evidence. Corrupt or incomplete v2 exports fail with
a nonzero exit status, as do JSON files over 16 MiB or an export over 32 MiB uncompressed.
Cached sync commits are only snapshot hints, not a revision attached to each answer. Hard is not an independently recorded guessed flag.
Small samples, repeats, different presentations, visible English and author/reviewer familiarity prevent interpreting accuracy as learning
effectiveness. Keep even the aggregate report private: it still identifies sources and content.

## License

Code: Apache-2.0 (see [LICENSE](LICENSE)). Content repos have their own licenses; data derived from
KANJIDIC2, KanjiVG and JMdict is CC BY-SA and requires attribution (shown in the app's About screen). Bundled fonts and their licences: [third_party/fonts](third_party/fonts/README.md).

The home screen illustrations (`drawable-nodpi`, `drawable-night-nodpi`) were generated with an AI image model (Azure OpenAI) from the prompts in [art/prompts.md](art/prompts.md) and [art/kana-prompt.md](art/kana-prompt.md); no existing artwork was supplied as input. Copyright in AI-generated images is legally unsettled in some jurisdictions, so they are shipped as part of the app without a separate licence claim.

The built-in kana writing references in `app/src/main/assets/kana-strokes.json` are converted from KanjiVG revision `70a0b7ae0c18ceb5cb358274b029cce0234a43bc` and remain under CC BY-SA 3.0. Regenerate them with `python tools/generate_kana_strokes.py` (requires `svgpathtools`). The About screen includes the corresponding attribution.

## Fonts
The quiz kanji uses bundled OFL fonts, subset to JIS X 0208 level 1 + ASCII (about 6 MB total, `app/src/main/res/font/kanji_*.ttf`):
Noto Serif JP (Mincho, wght 400), Klee One (textbook), Yuji Syuku (brush). Licences are in `third_party/fonts/`.
The plain face is the system CJK font. Which faces a card may use depends on its FSRS stability (`core/srs/FontPolicy`); a lapse resets stability, so the card drops back to plainer faces.
To regenerate a subset: `python -m fontTools.subset <font> --text-file=chars.txt --layout-features='' --no-hinting` (`pip install fonttools brotli`).
