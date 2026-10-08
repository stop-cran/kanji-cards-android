# Kanji Cards Android – working notes

Kotlin + Jetpack Compose app that studies kanji from a user-configurable GitHub content repo (default `stop-cran/learning-japanese`).
Plan: `docs/PLAN.md`. Content format: `docs/CONTENT-SCHEMA.md` and the content repo's `docs/content-format.md`.
Keep this file current: add a short note whenever you hit a gotcha, pitfall or non-obvious decision.

## Layout

- `core/` – pure Kotlin, no Android imports, unit-tested on the JVM: `content` (front matter, parser, safe zip, repo URL), `srs` (FSRS-5,
  queue builder), `quiz` (option builder). Put new logic here whenever possible (the stroke matcher too).
- `data/` – Room entities/DAOs (`Database.kt`), `Settings` (SharedPreferences), `ContentSync`, `SyncWorker`.
- `ui/` – Compose screens and view models. `MainActivity` holds a tiny string-route back stack (`home`, `settings`, `cards`, `quiz`, `card:<kanji>`).
- `KanjiApp` is the service locator (`db`, `settings`, `contentSync`).

## Build and test (Windows)

- Needs `JAVA_HOME` = JDK 17 and `ANDROID_HOME` (see `docs/PLAN.md`). Android Studio's Gradle JVM must be JDK 17 (Gradle 8.9 rejects JDK 25).
- `./gradlew assembleDebug testDebugUnitTest`. `ContentTest.parsesRealContentRepoWhenPresent` reads `../../learning-japanese` if that
  clone sits next to this repo, and is skipped otherwise.
- Emulator: Pixel 9, API 35. UI can be driven from the shell with `adb shell uiautomator dump` + `adb shell input tap`; read element bounds
  from the dump instead of guessing coordinates.

## Gotchas

- **Edge-to-edge on API 35:** content draws under the status/navigation bars; a button there is visible but untouchable. Every screen goes
  through `ui/Page`, which applies `statusBarsPadding`/`navigationBarsPadding` and a scroll container. Don't build screens without it.
- **System back:** `BackHandler` in `AppNav` pops the route stack; without it back closes the app.
- **ViewModel scope:** `viewModel()` is scoped to the Activity, not to a route, so a quiz VM outlives leaving the screen and would resume
  mid-answer. The quiz route is `quiz:<timestamp>` and the VM is created with that key, giving each start a fresh session while still
  surviving rotation. Do the same for any new session-like screen.
- **Back stack route strings:** `home`, `settings`, `cards`, `quiz:<id>`, `card:<kanji>`.
- **Manifest hashes** are computed over LF-normalised bytes (`tools/build_manifest.py` in the content repo and `ContentParser.sha256`). Keep
  both in sync, otherwise files fail with "hash mismatch" on Windows checkouts with CRLF.
- **Content is untrusted.** Parse defensively, skip bad files instead of failing the sync, never render raw HTML, cap archive sizes.
- **Room:** list columns are joined with `SEP` (`\u001F`); use `joinSep()`/`splitSep()`. `review_log.id` is auto-generated, so pass `0`.
  Review state is keyed by (`sourceId` = lower-case `owner/repo`, kanji, mode). Never key it by title or position; titles are editable.
- **Quiz semantics:** options are meaning titles and unique by title (one correct answer). Wrong = Again (re-asked after 3 cards),
  right = Good, "I guessed" = Hard. Time-based Easy was dropped: with 4 options a fast answer is often luck.
- **Daily new-card budget** counts kanji whose first review log entry is today (`newCardsIntroducedSince`), per mode.
- **FSRS:** weights are the FSRS-5 defaults typed from memory; verify against the official table before relying on exact intervals. Sub-day
  reviews use the short-term stability formula, so same-session re-asks don't inflate stability.
- Compose in a `Column` scope: `Modifier.weight` only works in the matching `Row`/`Column` receiver; `Page`'s content lambda is a `ColumnScope`.

## Tooling gotchas (Windows / PowerShell)

- Run Gradle with `$env:JAVA_HOME` set explicitly; new shells don't inherit it.
- Commits to `stop-cran/*` repos must be GPG-signed (identity comes from the global `includeIf`). If signing hangs on the passphrase
  popup, the user is AFK: stop and ask them to sign again.
- Pushing needs the stop-cran token: `git -c credential.helper= -c "http.extraheader=Authorization: Basic <base64 x-access-token:TOKEN>" push`
  with `TOKEN = gh auth token --user stop-cran`.
- Non-ASCII output from Python/PowerShell needs `$env:PYTHONIOENCODING='utf-8'`.
- The file `create` tool refuses existing files and missing parent directories; create folders first.

## Markdown viewer and extra practice
- `core/markdown` is a small own parser (no library, no HTML). `Links.resolve` only allows `https://` and repo-relative `.md` links into `kanji/`, `words/`, `articles/`; anything else stays plain text. Content is untrusted.
- `ui/MarkdownView` renders blocks; doc links push a `doc:<path>` route (`DocScreen`), so Back returns to the quiz exactly where it was (quiz ViewModel is keyed by its route and survives).
- Do not call composable helpers from non-composable lambdas (table rows are a separate `@Composable`).
- Quiz routes: `quiz:<ts>` (due + new) and `quiz:<ts>:extra` (`QueueBuilder.extra`, 10 cards: overdue, unseen, soonest due). The ViewModel starts via `ensureStarted(extra)` from `LaunchedEffect`, never in `init`.

- Downloadable Google Fonts (`ui-text-google-fonts`) do NOT work for CJK: the provider serves only the Latin subset, and Compose falls back to the system face silently. Bundle subsetted fonts instead (see README "Fonts").

## Stroke matcher (`core/draw`)
- `StrokeMatcher.match(reference, drawn)` is pure Kotlin. It normalises the drawing onto the reference box (per-axis scale, refit on matched strokes), links drawn to reference strokes greedily by mean resampled distance (single, joined, broken candidates), then reports `Reversed`, `WrongOrder` (strokes outside the longest increasing run of reference indexes), `WrongShape`, `Joined`, `Broken`, `Missing`, `Extra`.
- Tolerances are in `MatcherConfig` (fractions of the 109 box). They were tuned only on SIMULATED hands (`StrokeMatcherTest.Hand`), so re-tune with real recorded drawings from a device before trusting them; add them as fixtures.
- The matcher does not identify the kanji: similar kanji share strokes. Identity comes from the recognition gate (ML Kit), the matcher only grades strokes.
