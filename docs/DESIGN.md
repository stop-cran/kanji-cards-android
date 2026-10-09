# Design: decisions, principles, invariants, evolution

Read this before changing behaviour. `PLAN.md` is the original plan, `CONTENT-SCHEMA.md` is the content format, and
`.github/copilot-instructions.md` lists build gotchas. This file records *why the app works the way it does*. Update it when a
decision changes (change the text, don't append a contradiction).

## 1. Purpose and principles

A personal kanji and vocabulary trainer for one learner who wants **depth tied to the cards**: every card links to articles about
usage, confusable kanji and word families, kept in a GitHub repo that the app pulls. Principles:

1. **Content is data, not code.** Cards, words, articles and stroke geometry live in the content repo (default `stop-cran/learning-japanese`,
   user-configurable). Fixing a card never needs an app release. The app must work with any fork that follows `CONTENT-SCHEMA.md`.
2. **Content is untrusted input.** Parse defensively, skip bad files and report them, never render raw HTML, cap archive sizes, allow only
   `https://` and repo-relative links into `kanji/`, `words/`, `articles/`. A bad sync must never destroy last-known-good content.
3. **Don't reinforce mistakes.** Drawing grading is strict about order, direction, joined/broken strokes and hooks, because wrong habits are
   the thing being avoided; it is lenient about shape, size and position, because hands vary. When in doubt relax shape, not order.
4. **Offline, private, local.** After the first sync everything works offline. No accounts, analytics or uploads. Drawings are logged only
   if the user opts in, and only on the device. This keeps the Play data-safety form trivial (`PRIVACY.md`).
5. **Pure core.** Logic goes in `core/` (no Android imports, JVM unit tests); `data/` and `ui/` stay thin. A rule that can be tested in `core/`
   must live there.
6. **Never trust simulated tuning alone.** Matcher thresholds were first tuned on simulated hands; real exported drawings are fixtures
   (`RealDrawingsTest`). Prefer adding real fixtures over adjusting numbers blindly.

## 2. Content model

- Kanji card `kanji/<char>.md` (front matter: `kanji`, `title`, `jlpt`, `tags`, readings, `distractors`...). The file name is the identity;
  titles are editable and **must be unique** among kanji (duplicates are dropped with a problem report) because the meaning quiz offers titles
  as options.
- Word `words/<word>.md` (`word`, `reading`, `title`, `type`, `kanji` list, `tags`). Words have **no JLPT tag**. A word's level is derived from
  its hardest kanji (see §5).
- `articles/*.md` free-form; `strokes/<char>.json` KanjiVG-derived geometry in a 109-unit box with optional `orderVariants`.
- `manifest.json` maps path to SHA-256 over LF-normalised bytes; files failing the hash are skipped. The content repo's
  `tools/build_manifest.py` and `ContentParser.sha256` must stay in sync.
- Sync downloads the branch zip, but skips it when the branch head SHA equals the stored one (`force` bypasses). Import is one Room
  transaction: readers never see a half-imported snapshot.

## 3. Scheduling (FSRS-5)

- Every review goes through `Fsrs.review`; there is one `SrsState` per **(sourceId, stack, item, mode)**. The key is never a title or
  position. `sourceId` is the lower-case `owner/repo`, so switching to a fork keeps separate progress.
- **Stacks have independent state** on purpose: a kanji learned in a small set may be confused in a larger one and must be re-learned there.
  Kanji stacks are tag filters (`all`, `starter`, `jlpt-n5`...). Word stacks are `words-n5`, `words-n4`, `words-all` (cumulative by level).
- **Modes** are `Quiz`, `Draw`, `WordJpEn`, `WordEnJp`; each mode has its own state for the same item. Recognition and recall are different
  memories and must not share a schedule.
- Grades: wrong = Again (re-asked about 3 cards later, same session); right = Good; "I guessed" = Hard. Easy is never used (with 4 options a
  fast answer is often luck). Drawing: clean = Good, recognised with stroke mistakes = Hard, not recognised or given up = Again.
- Queue order (`QueueBuilder`): due cards by urgency (how overdue relative to the card's own interval) **plus noise** (default 0.3 of an
  interval), mixed with new cards drawn at random. Noise stops the same sequence acting as a cue. Whether a card is *due* never depends on noise.
- Daily new-card budget counts items whose first log entry is today, per stack and mode (`newCardsIntroducedSince`).
- Extra practice (`QueueBuilder.extra`) goes through the normal FSRS update; early reviews are not special-cased.

## 4. Quiz mechanics

- Meaning quiz options are titles, unique by title, so exactly one answer is correct. Preference for wrong options: the card's own
  `distractors`, then most shared tags, then random.
- Word quiz: `JpToEn` shows the word and asks for the meaning; `EnToJp` shows the meaning (+ tags as a hint) and asks for `word (reading)`.
  Options never share a title or written form with the target.
- Font variety grows with memory stability (Gothic only when young, then Mincho, Textbook, Brush; a lapse drops it back) so recognition does not depend on one glyph shape (`FontPolicy`).
- Each session screen is keyed by its route (`quiz:<ts>[:extra]`, `wquiz:<ts>:<jp|en>[:extra]`, `draw:<ts>[:extra]`) so a ViewModel
  survives rotation and following a doc link, but a new start is a fresh session. Start sessions from `LaunchedEffect`, not `init`.

## 5. Words, levels and advancement

- Word level = the **minimum** `jlpt` number among its kanji (N5 = 5 is easiest, so the minimum is the hardest). A word with no kanji or an
  unknown kanji has no level and appears only in "All words".
- Default scope is N5. N4 (and All) appear only after the user unlocks them; the unlock is **offered, never forced**, when most N5 items are
  solid across all modes (`Advancement`): stability of at least 7 days and recall of at least 85%, at least 85% overall and 60% in each mode,
  at least 20 items. Dismissal hides the offer for 7 days.
- `EnToJp` (harder recall) is offered per word only after 2 answers in `JpToEn`, or once already started.
- **Cross-direction blending affects ordering only.** When ordering one direction, urgency is multiplied by `1 - 0.2 * R_other`
  (retrievability of the other direction; unseen or forgotten changes nothing). Due dates and stored states of each direction are never
  changed by the other. Don't make weights additive: FSRS stability and difficulty are not additive quantities.

## 6. Drawing and the matcher

- Two stages. (1) ML Kit Digital Ink must recognise the target within its top candidates (the *gate*: is it this kanji at all?). If ML Kit is
  unavailable the matcher alone decides. (2) `StrokeMatcher` compares drawn strokes with KanjiVG reference strokes and reports issues; it
  never identifies the kanji.
- Drawn strokes are regularised first (Douglas-Peucker at 2% of drawing size, resampled to 24 points), so tiny kinks are ignored. Small
  details, including a tiny hook, are removed and not counted.
- Issues: `Reversed`, `WrongOrder`, `WrongShape`, `Joined`, `Broken`, `Missing`, `Extra`, plus `MissingHook` / `ExtraHook` (stroke-end hooks,
  `EndHook.kt`; checked only on otherwise clean single-stroke links; extra-hook thresholds are deliberately high to avoid false alarms).
- Accepted alternative orders come from `orderVariants` in the strokes file; there is no source for them yet (issue #2).
- The displayed "correct strokes" are jittered for display only; grading always uses the unmodified reference and the raw points.
- Short strokes (dots, ticks) get a looser shape limit; hands vary most there.

## 7. UI and platform constraints

- Everything goes through `ui/Page` (edge-to-edge insets, scrolling). Back is a string-route stack in `MainActivity`.
- Theme follows the system (light/dark); drawing canvases stay paper-white on purpose.
- Home loads asynchronously. Flows start as "not loaded" (null), never as empty, and the screen shows a progress bar until all are loaded;
  empty means "no data", null means "not yet".
- Release: signed with an upload key (git-ignored `keystore.properties`), R8 and resource shrinking on, Play App Signing recommended.

## 8. Invariants (don't break these; tests cover most)

1. Review state is never keyed by title or position; every DAO query takes the stack.
2. A failed or partial sync leaves the previous content intact.
3. Hash mismatch, unsafe path or malformed file skips that file only.
4. Meaning-quiz and word-quiz options have exactly one correct answer.
5. Stacks, modes and directions never share SRS state; only ordering may look across directions.
6. Grading uses raw/regularised drawn points and the unmodified reference; display jitter and brush smoothing never leak into grading.
7. No network use beyond: content download from `github.com`/`codeload.github.com`, and the one-off ML Kit model download.
8. Room schema changes need a migration; `exportSchema` is off, so be careful.
9. Source files have mixed LF/CRLF: check before multi-line text replacements.

## 9. Known gaps and evolution

- **Matcher tuning** from real drawings (more fixtures; the hook thresholds are initial guesses; 北 stroke 2 was flagged red and not yet
  investigated).
- **`orderVariants` data** (issue #2) and **writing-pattern rules** (issue #1).
- **Home load time** after a sync: the home screen observes whole kanji and word rows (bodies and stroke JSON); project only the columns
  it needs.
- **Word content**: mixed on+kun word type, weighting new cards towards common kanji, reading-only word mode (reading to meaning).
- **Stacks**: custom user stacks, per-stack daily limits, N3 and above, a word stack per kanji level beyond N4.
- **Words in browse and search**; romaji search input.
- **Scheduling**: fit FSRS weights from the review log after enough history; desired-retention setting.
- **Portability**: backup/export of review state (it lives only in the app's database), and iOS is out of scope.
- **Release**: Play listing, data-safety form, check the image model's output terms for the icon (`RELEASE.md`).
