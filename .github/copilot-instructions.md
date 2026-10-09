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

## Drawing mode
- Grading: `DrawGrader` combines the ML Kit Digital Ink gate (target in top 5 candidates) with `StrokeMatcher`. Clean = Good, recognised with stroke mistakes = Hard, not recognised = Again. If ML Kit is unavailable (`candidates` returns null) it falls back to matcher-only (`match.recognizable`).
- ML Kit `RemoteModelManager.download()` returns `Task<Void>`: success arrives as a null result, so never treat null as failure (this bug showed "model unavailable" while it worked). The model is a one-off download needing network.
- `DrawingPad` consumes pointer events so the screen does not scroll while drawing. Test on the emulator with `adb shell input swipe` (draw order matters).
- Matcher tolerances are tuned on simulated hands only; re-tune with real device fixtures.
- Short reference strokes (dots, ticks) get a looser shape limit (MatcherConfig.shortStrokeBonus, tapering from 15 to 50 units) because real hands vary most there; dotPlacedAndSizedLooselyIsAccepted covers a real 下 dot.
- Drawn strokes are regularised before matching (Douglas-Peucker with MatcherConfig.smoothing = 2% of drawing size, then resampled), so tiny kinks are ignored. Gotcha: after matcher changes reinstall the APK before asking for emulator re-checks.
- shapeLimit is 0.13: real hands drew 亻's slash ~30% short and the 0.10 limit rejected it (shorterSlashIsAccepted). Prefer relaxing shape tolerance over strictness; order, direction and join/break checks stay strict.
- MarkdownView is wrapped in SelectionContainer (copy/search works, links still clickable). The answer overlay shows the regularised drawing (core/draw/Regularize.kt, shared with the matcher).
- The correct-strokes panel is jittered per display (core/draw/Variation.kt: shift, rotation, scale, bow; display only, grading uses the unmodified reference). Follow-up for real variants and rules: issue #1.
- Opt-in drawing log (Settings, off by default): DrawingLog writes one JSON per checked drawing to app-private iles/drawings/ (strokes as [x,y,t] canvas px, outcome, issues, ML candidates). Never uploaded. Pull from a debug build: `adb shell run-as io.github.stopcran.kanji cat files/drawings/<file>.json`. Turn these into matcher fixtures. Play data-safety: local-only, nothing collected.
- Emulator gotcha: drawing 'I don't remember' always grades Again, so a session of only give-ups never reaches Done.
- Brushes (`ui/Brush.kt`, setting `Settings.brush`, default Chisel): Dot, Chisel nib, Soft, Ink. Used for the pad, the answer drawing and the reference via `LocalBrush`; sizes are fractions of the canvas width so panels look alike. Width comes from direction (chisel) or position along the stroke (soft/ink); grading always uses the raw points. Gotcha: filled path quads with NonZero cancel where orientations differ, so the chisel normalises quad orientation. Speed/pressure-based width is not implemented (a finger has no pressure; the answer view loses timing after regularising).
- Fast strokes: DrawingPad also keeps change.historical touch samples (denser data for grading). drawBrushStroke runs smoothLongSegments (display only, Catmull-Rom for segments over 1.2% of canvas width); grading always uses the raw points.
- DrawingPad clamps touch points to the pad bounds and clips drawing, so strokes cannot leave the square.
- Browse cards has a mixed search (core/content/CardSearch.kt): kanji, English title words, and readings (katakana folded to hiragana; dots and dashes ignored); exact matches rank first; a katakana query prefers on'yomi and a hiragana query prefers kun'yomi (other kind still matches, lower). Romaji input is not supported yet.

## Gotchas
- Review state is keyed by (sourceId, stack, kanji, mode); every DAO query takes a stack. Room schema bumps need a migration (see Database.kt).
- Content sync skips the download when the branch head SHA equals the stored one; use force=true to bypass.
- Release signing reads git-ignored keystore.properties; never commit keystores. See docs/RELEASE.md.
- Source files have mixed LF/CRLF line endings; check before multi-line text replacements.
- Word `jlpt` is optional vocabulary metadata, not a kanji-derived value to persist. `inWordStack` applies explicit levels first, then the legacy fallback for null; all queue/progression callers share it. Room v3 keeps IDs/SRS state and clears only sync metadata so previously ignored labels are reimported even from an unchanged revision.
- Only omitted word `jlpt` enables fallback: present values must be top-level unquoted integers 1-5. Preserve raw front-matter entries to reject blank/quoted/list values and duplicate or multiline shadowing without changing kanji parsing. Never synthesize level tags; word tags and quiz tag scoring stay independent of `jlpt`.
- `DatabaseMigrationTest` runs Room upgrades/reopen tests on Robolectric native SQLite (API 28), without the content repo or an emulator. Room 2.6 databases are not `Closeable`; close them explicitly in `finally` rather than using Kotlin `use`.
