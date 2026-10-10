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
## License

Code: Apache-2.0 (see [LICENSE](LICENSE)). Content repos have their own licenses; data derived from
KANJIDIC2, KanjiVG and JMdict is CC BY-SA and requires attribution (shown in the app's About screen). Bundled fonts and their licences: [third_party/fonts](third_party/fonts/README.md).

## Fonts
The quiz kanji uses bundled OFL fonts, subset to JIS X 0208 level 1 + ASCII (about 6 MB total, `app/src/main/res/font/kanji_*.ttf`):
Noto Serif JP (Mincho, wght 400), Klee One (textbook), Yuji Syuku (brush). Licences are in `third_party/fonts/`.
The plain face is the system CJK font. Which faces a card may use depends on its FSRS stability (`core/srs/FontPolicy`); a lapse resets stability, so the card drops back to plainer faces.
To regenerate a subset: `python -m fontTools.subset <font> --text-file=chars.txt --layout-features='' --no-hinting` (`pip install fonttools brotli`).
