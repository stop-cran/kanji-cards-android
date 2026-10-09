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
4. **Offline-first, private.** Studying works offline after the first sync; sync, the one-off ML Kit model download and external links need
   network. No accounts, analytics or uploads. Drawings are logged only if the user opts in, stay on the device and can be exported as a zip
   by the user. Android backup may copy app data (`data_extraction_rules.xml`). This keeps the Play data-safety form trivial (`PRIVACY.md`).
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
- Sync checks the branch head via `api.github.com`, then downloads the zip. It is skipped when the head SHA equals the stored one, and the
  import is also skipped when `manifest.contentVersion` is unchanged, so **content changes must bump `contentVersion`** (`force` bypasses
  both). If the head lookup fails the zip is downloaded anyway.
- Import is one Room `@Transaction` (`replaceContent`) that replaces the whole snapshot: readers never see a half-imported snapshot.
  Consequences: a *fatal* failure (exception, no valid kanji, unsafe zip entry or archive limits in `SafeZip`) keeps the previous content;
  a *successful partial* import (some manifest files skipped for hash/parse errors) replaces it, so a skipped file disappears until fixed.
  Only files listed in the manifest are imported. For duplicate kanji titles the first in sorted path order is kept.
- Only `replaceContent` may clear/insert content tables; the synced SHA is stored after it succeeds.

## 3. Scheduling (FSRS-5)

- Every review goes through `Fsrs.review`; there is one `SrsState` per **(sourceId, stack, item, mode)**. The key is never a title or
  position. `sourceId` is the lower-case `owner/repo`, so switching to a fork keeps separate progress.
- **Stacks have independent state** on purpose: a kanji learned in a small set may be confused in a larger one and must be re-learned there.
  Kanji stacks are tag filters (`all`, `starter`, `jlpt-n5`...). Word stacks are `words-n5`, `words-n4`, `words-all` (cumulative by level).
- **Modes** are `Quiz`, `Draw`, `WordJpEn`, `WordEnJp`, `WordReading`; each mode has its own state for the same item. Recognition and recall are different
  memories and must not share a schedule.
- Grades: wrong = Again (FSRS schedules it about 10 minutes later in a learning state; the quiz view models also re-insert it about 3 cards
  later in the same session, and that re-insert is session-only); right = Good; "I guessed" = Hard. Easy is never used (with 4 options a
  fast answer is often luck). Drawing: clean = Good, recognised with stroke mistakes = Hard, not recognised or given up = Again.
- Queue order (`QueueBuilder`): due cards by urgency (how overdue relative to the card's own interval) **plus noise** (default 0.3 of an
  interval), mixed with new cards drawn at random. Noise stops the same sequence acting as a cue. Whether a card is *due* never depends on noise.
- Daily new-card budget counts items whose first log entry is today, per stack and mode (`newCardsIntroducedSince`).
- Extra practice (`QueueBuilder.extra`) goes through the normal FSRS update; there is no "practice doesn't count" path. FSRS itself handles
  sub-day reviews with the short-term stability formula (don't remove it).
- Review state and its log entry are written in one transaction (`ReviewDao.record`). Reminders back off at growing gaps (1/2/4/7/14 days)
  and stop until the user studies again (`ReminderPolicy`).

## 4. Quiz mechanics

- Meaning quiz options are titles, unique by title, so exactly one answer is correct. Preference for wrong options: the card's own
  `distractors`, then most shared tags, then random.
- Word quiz: `JpToEn` shows the word and asks for the meaning; `EnToJp` shows the meaning (+ tags as a hint) and asks for `word (reading)`.
  Options never share a title or written form with the target.
- Reading quiz (`WordDirection.Reading`): shows the word and asks for its kana reading. Only words whose reading differs from the written form
  (`hasReadingToLearn`) are asked. Options (`ReadingOptions`) are all distinct readings, never equal to the correct one: generated
  look-alikes (voiced/unvoiced, long/short vowel, small っ) first, then real readings of words sharing a kanji or of similar shape. The option
  key is the reading (`WordQuizUi.Answer.key`), unlike the other directions where it is the written word. Tap only for now (voice: issue #5).
- Font variety grows with memory stability (Gothic only when young, then Mincho, Textbook, Brush; a lapse drops it back) so recognition does not depend on one glyph shape (`FontPolicy`).
- Each session screen is keyed by its route (`quiz:<ts>[:extra]`, `wquiz:<ts>:<jp|en|rd>[:extra]`, `draw:<ts>[:extra]`) so a ViewModel
  survives rotation and following a doc link, but a new start is a fresh session. Start sessions from `LaunchedEffect`, not `init`.

## 5. Words, levels and advancement

- Word stacks are cumulative in *membership* (`words-n4` includes N5 words) but keyed independently in SRS state, so switching from `words-n5`
  to `words-n4` starts those words afresh. Never share state across stacks.
- Word level = the **minimum** `jlpt` number among its kanji (N5 = 5 is easiest, so the minimum is the hardest). A word with no kanji or an
  unknown kanji has no level and appears only in "All words".
- Word scope defaults to N5. N4 (and All) appear only after the user unlocks them; the unlock is **offered, never forced** (`Advancement`).
  It evaluates the N5 kanji of the *currently selected kanji stack* (quiz and draw state of that stack) and, when the `words-n5` stack is
  selected, the N5 words in every direction (the reading quiz counts only N5 words with kanji). An item is solid at stability of at least 7 days and recall of at least 85%; the offer needs 85%
  solid overall, 60% in each non-empty mode (modes with no items, e.g. kanji without stroke data, are ignored) and at least 20 item-mode
  entries. Dismissal hides the offer for 7 days.
- `EnToJp` and `Reading` (harder recall) are offered per word only after 2 answers in `JpToEn`, or once already started (`WordDirection.gated`).
- **Cross-direction blending affects ordering and eligibility only.** Eligibility: the `gated` directions above. `Reading` is gated by `JpToEn` but never deferred by it (knowing the meaning says nothing about the reading). Ordering: for *overdue* cards
  (positive urgency only; scaling a negative urgency would make it sooner) urgency is multiplied by `1 - 0.2 * R_other` (retrievability of
  the other direction; unseen or forgotten changes nothing). Due dates and stored states of each direction are never changed by the other. Don't make weights additive: FSRS stability and difficulty are not additive quantities.

## 6. Drawing and the matcher

- Two signals, combined in `DrawGrader`. ML Kit Digital Ink says whether the drawing reads as the target (top 5 candidates). `StrokeMatcher`
  compares strokes with the KanjiVG reference and reports issues; it never identifies the kanji. Outcome: a **clean match is Clean** even
  if ML Kit disagrees (avoids false rejects; the trade-off is that a different character within tolerance would pass); otherwise the target must
  be in ML Kit's candidates (not recognised = Again, recognised with issues = Hard). When ML Kit is unavailable (null candidates, e.g. model
  missing or timeout) the matcher alone decides (`recognizable`). Giving up passes an empty list, not null.
- Shape matching uses regularised strokes (Douglas-Peucker at 2% of drawing size, resampled to 24 points), so tiny kinks are ignored. Small
  details, including a tiny hook, are removed and not counted. Hook detection deliberately uses the Douglas-Peucker-simplified but
  **not resampled** points (resampling would erase the corners); never feed `regularize()` output into `endHook`.
- Issues: `Reversed`, `WrongOrder`, `WrongShape`, `Joined`, `Broken`, `Missing`, `Extra`, plus `MissingHook` / `ExtraHook` (stroke-end hooks,
  `EndHook.kt`; checked only when the whole drawing has no other issues, and only on single-stroke links; extra-hook thresholds are deliberately high to avoid false alarms).
- Accepted alternative orders come from `orderVariants` in the strokes file. The app already applies them; the authored data is what is missing (issue #2).
- The displayed "correct strokes" are jittered for display only; grading always uses the unmodified reference and the raw points.
- Short strokes (dots, ticks) get a looser shape limit; hands vary most there.

### Voice input (opt-in, on-device only)

- Voice only **picks among the displayed English options** in the JP-to-EN modes (kanji meaning quiz, word JP-to-EN). It never produces Japanese or kanji: homophones, alternative readings and kun-only ambiguity make that unreliable, and it would not help memorise the characters. EN-to-JP and drawing have no voice input.
- Detection: `VoiceSupport` needs API 31+ `isOnDeviceRecognitionAvailable`, and on API 33+ an installed English on-device pack (`checkRecognitionSupport`). If unsupported the button is hidden and Settings > Advanced explains why. **There is no fallback to the default (possibly networked) recognizer**, so the "no uploads" principle holds; keep it that way unless DESIGN section 1 and PRIVACY are revised first.
- `OptionMatcher` (pure, unit-tested) compares recogniser hypotheses only with the shown options; unclear or ambiguous means no result and the learner taps. A voice pick goes through the normal answer state and nothing is recorded until "Next", so a misheard pick can be taken back ("That's not what I said").
- Audio and recognised text are never stored or logged.

## 7. UI and platform constraints

- Everything goes through `ui/Page` (edge-to-edge insets, scrolling). Back is a string-route stack in `MainActivity`.
- Theme follows the system (light/dark); drawing canvases stay paper-white on purpose.
- Home loads asynchronously. The six main flows (kanji, words, four review-state sets) start as null = "not yet" and the screen shows a progress
  bar until all emit; empty means "no data". The new-card-introduced counts still start at 0 (meaning "none today") and settle a moment later.
- Home reads only slim rows (`observeKanjiLite`/`observeWordsLite`: no article body, `strokesJson` reduced to `''` or NULL). Screens that need bodies
  or strokes load a single card (`kanjiCard`, `word`). Never observe whole content rows in a list that recomposes on every state change.
- **Settings order: basic to advanced, grouped with dividers.** Groups: Studying (daily new cards, fonts, reminder), Drawing (brush), then Advanced
  (content repository, handwriting data), then About. Put new settings in the group they belong to by how often a typical learner needs them;
  anything that can break or reset the user's content or privacy posture goes under Advanced. Settings that are a single value apply
  immediately (no Save button); only the repository URL/branch need an explicit "Save & sync" because they trigger a download.
- Release: signed with an upload key (git-ignored `keystore.properties`), R8 and resource shrinking on, Play App Signing recommended.

## 8. Invariants (don't break these; tests cover most)

1. Review state is keyed by (sourceId, stack, item, mode), where item is the kanji character or word string, never a title, position or reading.
   Review-state reads and daily-new counts are scoped by source, stack and mode (reminder-history queries are global).
2. A failed sync (fatal error) leaves the previous content intact; the swap is atomic.
3. A hash mismatch or malformed manifest file skips that file only; an unsafe zip entry or limit violation aborts the whole sync.
4. Meaning-quiz and word-quiz options have exactly one correct answer.
5. Stacks, modes and directions never share SRS state; only ordering may look across directions.
6. Grading uses raw/regularised drawn points and the unmodified reference; display jitter and brush smoothing never leak into grading.
7. No network use beyond: GitHub (`api.github.com` head lookup, `codeload.github.com` download), the one-off ML Kit model download, and links the
   user taps (any `https://` link opens the browser; repo links must be exactly `kanji|words|articles/<file>.md`).
8. Room schema changes need a migration; there is no destructive fallback and `exportSchema` is off, so a missing migration crashes.
9. Source files have mixed LF/CRLF: check before multi-line text replacements.

## 9. Known gaps and evolution

- **Matcher tuning** from real drawings (more fixtures; the hook thresholds are initial guesses; 北 stroke 2 was flagged red and not yet
  investigated).
- **`orderVariants` data** (issue #2) and **writing-pattern rules** (issue #1).
- **Word content**: mixed on+kun word type, weighting new cards towards common kanji, reading-only word mode (reading to meaning).
- **Stacks**: custom user stacks, per-stack daily limits, word stacks beyond N4 (kanji stacks already cover N1-N5 when tagged).
- **Words in browse and search** (kanji search already supports kana and romaji).
- **Branch vs source id**: `sourceId` is `owner/repo` without the branch, so switching branches shares progress and may confuse the SHA cache. Investigate
  before encouraging branch switching.
- **Content freshness**: a bad publish that forgets to bump `contentVersion` stays hidden until a forced sync.
- **Scheduling**: fit FSRS weights from the review log after enough history; desired-retention setting.
- **Portability**: backup/export of review state (it lives only in the app's database), and iOS is out of scope.
- **Release**: Play listing, data-safety form, check the image model's output terms for the icon (`RELEASE.md`).
