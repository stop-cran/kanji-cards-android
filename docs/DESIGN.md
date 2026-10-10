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
- Word `words/<word>.md` (`word`, `reading`, `title`, `type`, `kanji` list, `tags`, optional `jlpt`, `quiz_exclusions` and `quiz_distractors`).
  An explicit community vocabulary level takes precedence over kanji difficulty; only omission enables the legacy fallback (see §5).
  Word tags remain free-form: no JLPT tags are required or generated, and level metadata does not affect quiz tag scoring.
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
- **Pacing** (`core/srs/Pacing.kt`, `data/Pacing.kt`): one shared pool of new items per day for all modes, kanji and words (distinct stack+item whose
  first log entry is today, `itemsIntroducedSince`). Allowance = base (`dailyNewCards`) x weekday level (Full 1, Light 0.5, Rest 0; default Sat/Sun
  Light, editable in Settings) x backlog factor. Backlog factor is 1 while due reviews <= capacity and falls linearly to 0 at twice capacity;
  capacity = median reviews on active days of the last 14 days, at least 40. Reviews are never capped; the home page explains a reduced
  allowance and shows "This week: X of Y study days" (Y = non-rest days, max 5; a hint, not a streak: missing a day never resets anything). Settings offers commitment presets (Light 5, Steady 10, Intensive 20 new a day; the
  number field stays for custom values). Allowance is shared by every consumer through `AppDatabase.newAllowance`.
- Scheduling tweaks: a new card's first non-Again answer in a multiple-choice mode (meaning, words, readings) is capped at 1 day, because a lucky guess
  is possible (drawing keeps FSRS' default); intervals of 3+ days get deterministic fuzz (+-10%, +-5% from a week, seeded by item, mode and
  reps) so cards learned together spread out. `FsrsTest` checks a sequence against py-fsrs 5.1.0.
- Extra practice (`QueueBuilder.extra`) goes through the normal FSRS update; there is no "practice doesn't count" path. FSRS itself handles
  sub-day reviews with the short-term stability formula (don't remove it).
- Review state and its log entry are written in one transaction (`ReviewDao.record`). Reminders back off at growing gaps (1/2/4/7/14 days)
  and stop until the user studies again (`ReminderPolicy`).

## 4. Quiz mechanics

- **Architecture:** the four session ViewModels (Quiz, Word, Reading, Draw) share the pure `core/quiz/QuizSession` (queue, tallies, grade choice, `Relearn` reinsertion; Draw uses `confirm = false`). They receive `Services(db, settings, context)` through `serviceViewModel(key) { … }` instead of casting `Application`. `HomeData` loads the seven state maps as one non-null `StateSet`; the N4 offer is the pure `shouldOfferN4`. `InkRecognizer` and `ContentSync` rethrow `CancellationException` and turn any other failure into a fallback / `SyncResult.Failed`.
- **Feedback and relearning:** every question offers "I don't know" (graded Again, no wrong pick; all three quizzes). A wrong pick says what it was ("that is 月", the word, or which kanji the reading belongs to) and the article is collapsed behind "Read the article" after a miss (open after a correct answer). A failed card is relearned in-session by `Relearn`: re-asked 3 cards later (recorded) and, if right, once more 7 cards later; that confirmation is practice only and is recorded only when wrong. Drawing with stroke mistakes (graded Hard) gets one re-ask; not-recognised is Again and re-asked. A repeat is never asked straight after itself: when no other card is queued the repeat is dropped (the answer is still recorded and FSRS schedules it), so a one-card "Practice more" session ends instead of looping. "Practice more" already uses every eligible card (up to 10), so short sessions in gated modes are legitimate; gates are never loosened to pad them.
- **Exposure before testing:** a kanji with no review state is first shown on a study view (`QuizUi.Study`: kanji, title, main readings, the article, "Got it — quiz me"), then
  asked. Showing it is not a review and records nothing; it is skipped for cards already started and not repeated after an "Again". **Drawing is gated behind
  recognition** (`DrawGate`): a kanji enters Draw once its meaning was answered well on two days (the readings' unlock) or was already drawn; home counts, the session
  and reminders all use the same predicate.

- Meaning quiz options are titles, unique by title, so exactly one answer is correct. Preference for wrong options: the card's own
  `distractors`, then most shared tags, then random.
- Word quiz: `JpToEn` shows the word and asks for the meaning; `EnToJp` shows the meaning (+ tags as a hint) and asks for `word (reading)`.
  Options deduplicate exact titles and written forms. A curated `quiz_exclusions` declaration on either word keeps the pair apart,
  both against the target and among chosen options, in both directions; exclusions are not transitive and do not infer synonyms.
  Scoring applies only to eligible candidates. A small pool yields fewer options, possibly only the target, rather than restoring an excluded word.
- Reading quiz (`WordDirection.Reading`): shows the word and asks for its kana reading. Only words whose reading differs from the written form
  (`hasReadingToLearn`) are asked. Options (`ReadingOptions`) are all distinct readings, never equal to the correct one: generated
  look-alikes (voiced/unvoiced, long/short vowel, small っ) first, then real readings of words sharing a kanji or of similar shape. The option
  key is the reading (`WordQuizUi.Answer.key`), unlike the other directions where it is the written word. Tap only for now (voice: issue #5).
  Curated `quiz_distractors` (at most 3 word IDs, never the word itself or an excluded pair; references to missing words are pruned with a sync problem) are offered first, but never fill more than `count - 2` slots so scored candidates still vary the options.
  Meaning-based `quiz_exclusions` do not filter reading options; reading uniqueness is checked independently.
- Kanji readings quiz (`core/reading`): "Pick the on'yomi / kun'yomi of 生" — one mode on the home page, two kinds scheduled separately
  (`StudyMode.KanjiOn`/`KanjiKun`), and **each reading is its own card**: the review item is (kanji, kind, reading), where the reading is the match key (`review_state.reading`, '' for all other modes), so failing ショウ never hides behind succeeding on セイ. **Exactly one option is valid:** the correct reading is of the asked kind;
  no other option may be any reading of the target in either kind (readings are shared between kinds, e.g. 気 キ/き) or a voicing / long-vowel /
  small-っ variant of one (`ReadingKey`: raw form from the card, kana-folded match key without `.`/`-`, display form: on in katakana, kun in
  hiragana). Fewer than two safe options means the question is skipped, never shown with a doubtful answer; a test builds questions for every
  card of the content snapshot. A kanji's readings are asked once its meaning has been answered without "Again" on two different days (or that kind was already
  started). Only the first three distinct kana stems of a kind become cards (`ReadingCard.quizReadings`: い.きる and い.かす are one reading); a further reading
  is introduced only after the previous one of that kind has reached the Review phase. Each new reading card costs one unit of the shared new-item
  allowance, counted per card (`review_log.reading`). Order only, nothing is withheld: due cards of both kinds are ranked globally (urgency in
  `URGENCY_EPSILON` buckets, then lower retrievability first so obscure readings of known kanji surface), then interleaved greedily so a kanji does
  not recur within `SIBLING_GAP` (2) cards while others remain (its readings would cue each other; interleaving also aids discrimination). A new card
  never jumps a due card skipped only for spacing; extra practice keeps its 10-card selection and is interleaved. Not part of N4 advancement (yet); tap only (voice: later).
- Font variety grows with memory stability (Gothic only when young, then Mincho, Textbook, Brush; a lapse drops it back) so recognition does not depend on one glyph shape (`FontPolicy`).
- Each session screen is keyed by its route (`quiz:<ts>[:extra]`, `wquiz:<ts>:<jp|en|rd>[:extra]`, `draw:<ts>[:extra]`, `kreading:<ts>[:extra]`) so a ViewModel
  survives rotation and following a doc link, but a new start is a fresh session. Start sessions from `LaunchedEffect`, not `init`.

## 5. Words, levels and advancement

- Word stacks are cumulative in *membership* (`words-n4` includes N5 words) but keyed independently in SRS state, so switching from `words-n5`
  to `words-n4` starts those words afresh. Never share state across stacks.
- An explicit word `jlpt` wins, including for kana-only words or words with harder, unknown or missing kanji. Only when it is omitted does
  the level fall back to the **minimum** `jlpt` number among the word's kanji (N5 = 5 is easiest, so the minimum is the hardest).
  An unlabelled word with no kanji or an unknown kanji level appears only in "All words". Shared `inWordStack` filtering keeps home counts,
  advancement, session queues and reminders consistent; changing level changes membership, not identity or stored SRS state.
- Word scope defaults to N5. N4 (and All) appear only after the user unlocks them; the unlock is **offered, never forced** (`Advancement`). The Words page always names the active deck, and Settings has a switch that sets the same `n4Unlocked` flag manually (turning it off falls back to N5).
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
- **Lookalikes**: a clean match is still rejected (NotRecognized = Again) when another kanji with the same stroke count, from any stack of the source,
  fits the drawing clearly better (`LookalikeGate`; catches 牛 for 午, where only a crossing-vs-attachment relation differs). Otherwise, when not
  recognised, the recogniser's top-1 candidate, if a single Han character other than the target, is shown as "That looks like X (meaning)" with a link to
  its article when it has a card. The per-stroke issue list and red flags are shown only for the Mistakes outcome.
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
- Word voice picks use the same exclusion-filtered choices as taps. Retrying restores those choices rather than rebuilding a larger pool.
- Audio and recognised text are never stored or logged.

## 7. UI and platform constraints

- Everything goes through `ui/Page` (edge-to-edge insets, scrolling). Back is a string-route stack in `MainActivity`.
- Theme follows the system (light/dark); drawing canvases stay paper-white on purpose.
- **Large screens:** `Page` caps content at 720dp (centred). Cards/Words browsing goes two-pane (list 40%, article 60%, `maxWidth` 1400dp) when `isExpandedWidth()` (>= 840dp); selecting a card shows the article in place, links inside it push a normal doc route. Articles render in a `LazyColumn` (`LazyMarkdownView`; `DocBody`), so a long article composes only visible blocks. A `weight` modifier must go on the `SelectionContainer` wrapper, not its child (parent data is lost otherwise). Checked on the Pixel Tablet AVD only.
- **Strings:** composable `Text("...")` literals live in `res/values/strings.xml` (`stringResource`, `%1$s` args). ViewModel messages, content descriptions and notification text are still literals; there are no translations yet.
- **Rare glyphs:** old components in articles (e.g. 𠯑 in 話) are CJK Ext. A/B/radical characters that phone system fonts often lack. `RareHan` draws
  characters in those ranges from a bundled subset of HanaMin (`res/font/rare_han_a/b.otf`, a few KB, generated by `tools/make_rare_font.py`, notice in
  `third_party/fonts`). `RareHanTest` fails when the content uses a rare character the subset does not cover; regenerate and update its list.
- **Home structure: a short main screen plus a Kanji page and a Words page** (`HomePages`: routes `home`, `kanji-home`, `words-home`). The main
  screen shows status, the N4 offer and two entries with one-line summaries; each page lists its modes. **One button per mode**, never a disabled
  "Start" next to an enabled "Extra practice": `ModeState` (`core/home`) is `Loading | Start(due,new) | PracticeMore | Locked(reason) | Unavailable(reason)`,
  computed from the same eligibility predicates the session view models enforce (a quiz needs two cards, drawing one stroke card, ...) so a
  tappable button never leads to an empty session. The reason for a disabled button is visible text and part of the row's accessibility state.
  All counts come from one `HomeData` (`rememberHomeData`), shared by the main screen and both pages, built from strict noise-free queues; a one-minute
  tick makes newly due cards and the day rollover show up. Rows stay in `Loading` until content, review states and introduced-today counts are all known.
  Card browsing lives on each page, since the sets differ: the Kanji page shows the card count and "Browse cards" (route `cards`, `CardSearch`), the
  Words page shows the word count and "Browse words" (route `word-cards`, `WordSearch`: written form, kana in either script, romaji ranked below kana,
  English title words). Both open the card's article; the main screen only notes the source when it is not the default repo.
  After process death session routes are dropped from the restored back stack (a session is not resumable) and the user lands on the page they started from.
- Home loads asynchronously. The nine main flows (kanji, words, seven review-state sets) start as null = "not yet" and the screen shows a progress
  bar until all emit; empty means "no data". The new-card-introduced counts still start at 0 (meaning "none today") and settle a moment later.
- Home reads only slim rows (`observeKanjiLite`/`observeWordsLite`: no article body, `strokesJson` reduced to `''` or NULL). Screens that need bodies
  or strokes load a single card (`kanjiCard`, `word`). Slim word rows retain `jlpt` and `quizExclusions`; omitting metadata from a projection must
  not turn explicit levels into the legacy fallback or drop curated exclusions. Never observe whole content rows in a list that recomposes on every state change.
- **Settings order: basic to advanced, grouped with dividers.** Groups: Studying (new items per day and weekly rhythm, fonts, reminder), Drawing (brush), then Advanced
  (content repository, handwriting data), then About. Put new settings in the group they belong to by how often a typical learner needs them;
  anything that can break or reset the user's content or privacy posture goes under Advanced. Settings that are a single value apply
  immediately (no Save button); only the repository URL/branch need an explicit "Save & sync" because they trigger a download.
- Release: signed with an upload key (git-ignored `keystore.properties`), R8 and resource shrinking on, Play App Signing recommended.

## 8. Invariants (don't break these; tests cover most)

1. Review state is keyed by (sourceId, stack, item, mode, reading), where item is the kanji character or word string, never a title or position; the kanji readings modes add the reading match key (`ReadingId`).
   Review-state reads are scoped by source, stack and mode; the new-item allowance is shared across stacks and modes of a source (reminder-history queries are global).
2. A failed sync (fatal error) leaves the previous content intact; the swap is atomic.
3. A hash mismatch or malformed manifest file skips that file only; an unsafe zip entry or limit violation aborts the whole sync.
4. Each quiz grades one designated target. Meaning-quiz word options enforce exact title/form deduplication and curated exclusions; these
   checks do not guarantee semantic uniqueness for uncurated meanings. Reading options have distinct reading keys.
5. Stacks, modes, directions and reading kinds never share SRS state; only ordering may look across directions.
6. Grading uses raw/regularised drawn points and the unmodified reference; display jitter and brush smoothing never leak into grading.
7. No network use beyond: GitHub (`api.github.com` head lookup, `codeload.github.com` download), the one-off ML Kit model download, and links the
   user taps (any `https://` link opens the browser; repo links must be exactly `kanji|words|articles/<file>.md`).
8. Room schema changes need a migration; there is no destructive fallback and `exportSchema` is on (schemas in `app/schemas`), so a missing migration crashes.
9. Source files have mixed LF/CRLF: check before multi-line text replacements.

Testing seams: network goes through `ContentHttp` (tests use a fake), view models are built with `serviceViewModel` over in-memory Room, and Compose
UI tests (`ComposeUiTest`) run on Robolectric for the stateless pieces (answer feedback, article toggle, page chrome). Static analysis is Detekt
with a baseline (`config/detekt`); new code must add no findings, and the baseline should only shrink.

## 9. Known gaps and evolution

- **Matcher tuning** from real drawings (more fixtures; the hook thresholds are initial guesses; 北 stroke 2 was flagged red and not yet
  investigated).
- **`orderVariants` data** (issue #2) and **writing-pattern rules** (issue #1).
- **Word content**: mixed on+kun word type, weighting new cards towards common kanji, reading-only word mode (reading to meaning).
- **Stacks**: custom user stacks, per-stack daily limits, word stacks beyond N4 (kanji stacks already cover N1-N5 when tagged).
- **Branch vs source id**: `sourceId` is `owner/repo` without the branch, so switching branches shares progress and may confuse the SHA cache. Investigate
  before encouraging branch switching.
- **Content freshness**: a bad publish that forgets to bump `contentVersion` stays hidden until a forced sync.
- **Scheduling**: fit FSRS weights from the review log after enough history; desired-retention setting.
- **Portability**: backup/export of review state (it lives only in the app's database), and iOS is out of scope.
- **Release**: Play listing, data-safety form, check the image model's output terms for the icon (`RELEASE.md`).
