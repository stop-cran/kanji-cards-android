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
