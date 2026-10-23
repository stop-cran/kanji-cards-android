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
- `grammar/<id>.json` schema v1 holds constructions/answer classes, relationships and contextual exercises, linked to an article.
  The bounded formal-noun pilot has 401 reviewed exercises in 32 sets, including five mixed sets (content commit `c2a9844`).
  This is scoped coverage, not a claim to cover every use or all Japanese grammar. Accepted alternatives share one exercise identity;
  every literal completion must fit the visible task, and accepted equivalents must not become distractors.
  Optional `additionalCorrectOptions` explicitly authors alternative choice realizations with their own IDs, labels and rationales.
  Base `options` retains exactly one correct representative for old clients. Additional labels must be accepted typed forms;
  normalized labels are distinct across both lists, and declared malformed grading metadata rejects the set.
  Optional exercise `jlpt` is one strict integer 1-5; omission is unclassified, not a vocabulary-derived fallback or inferred easiest level.
- `manifest.json` maps path to SHA-256 over LF-normalised bytes; files failing the hash are skipped. The content repo's
  `tools/build_manifest.py` and `ContentParser.sha256` must stay in sync.
- Sync checks the branch head via `api.github.com`, then downloads the zip pinned to that commit SHA (`RepoSource.commitZipUrl`), so the
  archive cannot differ from the checked head. It is skipped when the head SHA equals the stored one, and the
  import is also skipped when `manifest.contentVersion` is unchanged, so **content changes must bump `contentVersion`** (`force` bypasses
  both). Pending Grammar or scene parser epochs also bypass these shortcuts and the launch-check throttle, but not bad-content/shrink protection.
  Current Grammar parser epoch 4 is recorded per source only after atomic replacement, retaining JLPT, groups, vocabulary and authored
  additional correct choices. The readiness thresholds stay at 1 for levels and 2 for groups. Epoch-0 caches permit only unguided All new
  intake; epoch 1 permits level-aware unguided intake; started reviews remain available throughout.
  If the head lookup fails and content exists, the branch archive is downloaded at most once per 6h per source/branch (persisted
  `fallback:` timestamp; throttled calls return a non-retryable failure so the worker does not loop); with no content it downloads at once.
  `SafeZip` drains skipped entries too and caps total inflated bytes (100 MB) besides the wanted-file cap.
- Import is one Room `@Transaction` (`replaceContent`) that replaces the whole snapshot: readers never see a half-imported snapshot.
  Consequences: a *fatal* failure (exception, no valid kanji, unsafe zip entry or archive limits in `SafeZip`) keeps the previous content;
  a *successful partial* import (some manifest files skipped for hash/parse errors) replaces it, so a skipped file disappears until fixed.
  Only files listed in the manifest are imported. For duplicate kanji titles the first in sorted path order is kept.
- Only `replaceContent` may clear/insert content tables; the synced SHA is stored after it succeeds.

## 3. Scheduling (FSRS-5)

- Every recorded review goes through `Fsrs.review`; there is one `SrsState` per **(sourceId, stack, item, mode, reading)**. The key is never a title or
  position. `sourceId` is the lower-case `owner/repo`, so switching to a fork keeps separate progress.
- **Stacks have independent state** on purpose: a kanji learned in a small set may be confused in a larger one and must be re-learned there.
  Kanji stacks are tag filters (`all`, `starter`, `jlpt-n5`...). Word stacks are `words-n5`, `words-n4`, `words-all` (cumulative by level) and the tag stack `words-onomatopoeia`.
- **Kanji/word modes** include `Quiz`, `Draw`, `WordJpEn`, `WordEnJp`, `WordReading`, `WordType`; these modes have independent state.
  **Grammar** deliberately treats choose/type as presentations of one exercise, not two modes: one state under
  `(sourceId, "grammar", exerciseId, "Grammar", "")`. Construction/relationship IDs are analytical labels, not extra schedules.
  Pilot-off new exercises are sampled randomly within the chosen JLPT scope and shared daily allowance. Opt-in focused introductions
  keep new material in one eligible group and use supported familiarity plus authored recommendations when choosing the next group.
  The [introduction contract](GRAMMAR-INTRODUCTIONS.md) specifies the implemented scope, focus, evidence and exploration rules;
  none creates another schedule or restricts existing due reviews.
- **Typed words** (`WordType`, `core/words/TypedAnswer.kt`): English prompt, free-text answer, no options. Gate is `WordDirection.gate` (EnToJp, twice answered).
  Exact written form or a same-title/same-type synonym = Good; kana reading of a kanji word = Hard (re-asked); else Again. IME conversion is accepted as typed;
  kana-to-kanji recall is left to other modes. No first-success cap. Its new items share the daily `newBudget`.
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
- Extra practice (`QueueBuilder.extra`) uses normal FSRS updates for recorded answers; successful practice-only confirmations remain
  unrecorded as described below. FSRS handles sub-day reviews with the short-term stability formula (don't remove it).
- Review state and its log entry are written in one transaction (`ReviewDao.record`). Reminders back off at growing gaps (1/2/4/7/14 days)
  and stop until the user studies again (`ReminderPolicy`).

## 4. Quiz mechanics

- **Architecture:** the Grammar, Quiz, Word, Reading and Draw session ViewModels share the pure `core/quiz/QuizSession` (queue, tallies, grade choice, `Relearn` reinsertion; Draw uses `confirm = false`). They receive `Services(db, settings, context)` through `serviceViewModel(key) { … }` instead of casting `Application`. `HomeData` loads the seven state maps as one non-null `StateSet`; the N4 offer is the pure `shouldOfferN4`. `InkRecognizer` and `ContentSync` rethrow `CancellationException` and turn any other failure into a fallback / `SyncResult.Failed`.
- **Grammar questions:** a session starts with a shuffled choice question and alternates choose/type, including re-asks. Both show the
  complete Japanese prompt and English meaning; checking reveals evidence, explanation and all option rationales. The article opens on
  the normal document stack and Back preserves the current question/answer. Grammar-only choices with authored alternatives independently
  show two correct options on 20% of eligible choice appearances, not 20% of all questions and not a session quota. One random alternative
  is appended to the primary and every original distractor, then shuffled. No frequency setting or spelling-derived choices are added.
  One tap on any displayed correct option is sufficient. The captured order/labels/rationales/correct-ID set governs grading and feedback;
  callbacks carry the originating presentation identity so reused IDs cannot grade a later question. After a mistake or give-up, both
  correct options have explicit checkmarks/labels and correct colors; a selected wrong option is separately marked. A correct alternate
  completes the answer-side prompt with its captured label. Counters and all other quiz modes are unchanged. Typing trims and NFKC-normalizes
  both input and all accepted answers, then requires exact membership (no script folding, transliteration or typo tolerance).
  Input is limited to 80 characters. Empty Check does nothing; "I don't know" is Again. Correct is Good, or Hard when a correct-choice user
  selects "I guessed"; choice first success is capped at one day, typing is not. Advancing a recorded answer atomically saves one state,
  one canonical review log and one linked response detail. A successful confirmation is still practice only, regardless of presentation.
- **Grammar vocabulary:** optional reviewed occurrence ranges link lexical words in both question and answer-check prompts, never the
  blank or its inserted answer. All supported words are underlined/tappable; heavier links indicate insufficient familiarity evidence.
  A separate vocabulary list supplies large labeled buttons, including local-gloss-only words. The popup shows the displayed-form
  reading, contextual gloss and differing headword without grading or enrolling anything.
  At session preparation, one Room transaction captures Grammar data, referenced word-article text and same-source `WordJpEn` states
  across stacks. Any state satisfying `Advancement.isSolid` makes that canonical word familiar for the session; other modes/sources,
  kanji knowledge and JLPT do not. This is a word-level hint, not proof of knowing every sense/reading.
  Lookup/list/article state lives beside (not inside) the question state. Captured word-article text opens in a saveable panel within
  the same session; deeper document links and the answer-side grammar article use a source-pinned document route. Sync/source changes
  cannot retarget a lookup or replace its captured text. The existing grammar draft/options/order remain intact across panel and
  document navigation. There is no per-lookup telemetry, review, session, introduction-budget debit or grade adjustment.
- **Grammar scope (Phase B):** source-scoped All/Unclassified/N5-N1 controls select cumulative new material independently from exact-level
  article browsing; counts describe matching exercises, not whole matching files. `grammarStudy` supplies Home, sessions and reminders
  from the shared `GrammarEligibility` pool and optional focus policy.
  Started, still-imported exercises remain eligible at every level; only unseen candidates are filtered in ordinary and extra practice.
  One authored exercise is askable without generating distractors. Pilot-off selection stays random within scope and the shared allowance;
  Practice more keeps overdue/new/upcoming ordering outside that cap. Changes affect the next session and never reset SRS. Home refreshes
  on source/scope/budget changes and completed content replacement; unavailable old-cache metadata is explained rather than counted as Unclassified.
- **Focused Grammar introductions (Phases C/D):** an opt-in source-scoped cursor keeps ordinary new material in one eligible contrast group,
  at most two recorded first introductions per facet per activation. Pure `GrammarFocus` and `GrammarEvidence` implement the
  [reviewed contract](GRAMMAR-INTRODUCTIONS.md): conservative 21-day familiarity, separate exposure coverage, explicit change/revisit,
  safe compatibility groups, deterministic authored-first selection and every-third-activation exploration. Phase D ranks by maximum
  incoming authored importance from supported familiar groups, with score-zero exploration inside the unfamiliar-first eligible pool.
  The local readout shows strongest links and source evidence, without raw responses. Home previews are read-only;
  session reservations and revision checks prevent stale answers from spending a
  newer activation's quota. Extra practice bypasses focus/prerequisites but not unseen-item level scope.
- **Feedback and relearning:** every question offers "I don't know" (graded Again, no wrong pick; all three quizzes). A wrong pick says what it was ("that is 月", the word, or which kanji the reading belongs to) and the article is collapsed behind "Read the article" after a miss (open after a correct answer). A failed card is relearned in-session by `Relearn`: re-asked 3 cards later (recorded) and, if right, once more 7 cards later; that confirmation is practice only and is recorded only when wrong. Drawing with stroke mistakes (graded Hard) gets one re-ask; not-recognised is Again and re-asked. A repeat is never asked straight after itself: when no other card is queued the repeat is dropped (the answer is still recorded and FSRS schedules it), so a one-card "Practice more" session ends instead of looping. "Practice more" in Meaning, Drawing and Readings is padded to a minimum size (`Settings.practiceMinimum()` = max(10, batch size)) by `core/srs/PracticePadding`: the shortfall is filled with the weakest (lowest FSRS retrievability) already-started cards of the same mode from the source's other stacks (never from `all`, never kanji of the current stack, never a New card), with a bounded boost for kanji sharing a radical or reading with the session ("confusable"). Gates are never loosened: no card is introduced. A borrowed card is answered and recorded in its owning stack (`owners` map in the view models); if several stacks hold it the lowest retrievability, then stack id, wins. The home "Practice more" button also appears when only padding candidates exist. Normal due sessions are not padded. The word quizzes (all four directions) pad the same way from the other word stacks: stacks keep separate states for a word, so a word learned in N5 and still unstarted in N4 qualifies (it must belong to the current stack for that direction, have no state here, and be started in another stack); similarity is a shared kanji. When a word session ends and its own "Practice more" would hold fewer than `PracticeSuggestion.WORTHWHILE` (5) cards, the button instead offers the best-stocked other direction ("Practice more: Japanese → English"); gated directions (Type, EN→JP, Reading) are short until their gate direction has been learned.
- **Tracing before drawing:** the first time a kanji with no Draw state is asked (setting "Trace new kanji first", on by default) the pad shows a light numbered reference to trace (`DrawGate.shouldTrace`). That attempt is practice only: checked and shown, but not graded, logged, saved as a diagnostic drawing or counted, and it creates no FSRS state. "Next" re-queues the kanji 3 cards later (`QuizSession.defer`) and that attempt is from memory and graded. Nothing is persisted, so an unfinished session traces again.
- **Exposure before testing:** a kanji with no review state is first shown on a study view (`QuizUi.Study`: kanji, title, main readings, the article, "Got it — quiz me"), then
  asked. Showing it is not a review and records nothing; it is skipped for cards already started and not repeated after an "Again". **Drawing is gated behind
  recognition** (`DrawGate`): a kanji enters Draw once its meaning was answered well on two days (the readings' unlock) or was already drawn; home counts, the session
  and reminders all use the same predicate.
- **Unlock rule and focus batch (settings):** `data/Unlock.kt` is the single provider of "meaning learned" (`meaningLearned(settings, …)`, over `ReviewDao.unlockLog`), used by Draw, readings, home and reminders. `UnlockRule.NextDay` (default) = two answers above Again on two local dates. `SpacedSameDay` additionally accepts two answers above Again after the latest Again that are ≥ 10 min apart with ≥ 10 other answers (any mode) in between (relaxation: next-day still unlocks). Practice-only repeats are never logged so never count. The optional **focus batch** (`core/focus/FocusBatch`, off by default, size 5–30) limits only first meaning introductions: a kanji is "in learning" when it was first answered after the per-stack cutoff (`Settings.focusSince`, set lazily, cleared when switched off) and any required card (meaning, Draw if stroke data exists, askable readings) is not yet in Review. New meaning cards = min(daily allowance, capacity); started kanji, due cards, unlocked Draw/readings and words are never blocked. With the default next-day unlock a full batch deliberately blocks new intake until its members unlock and finish. No index was added on the review log (per-stack logs are small).

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
- Each session screen is keyed by its route (`quiz:<ts>[:extra]`, `wquiz:<ts>:<jp|en|rd|ty>[:extra]`, `draw:<ts>[:extra]`, `kreading:<ts>[:extra]`) so a ViewModel
  survives rotation and following a doc link, but a new start is a fresh session. Start sessions from `LaunchedEffect`, not `init`.

### N5 listening

Listening schedules whole passages, not their three questions: `(sourceId, "listening", passageId, "Listening", "")`.
The N5-only learning contract is [owned with the content](https://github.com/stop-cran/learning-japanese/blob/main/docs/listening-n5.md).
It includes essential spoken vocabulary and speech demands, unlike grammar-only exercise classification.
Unapproved or invalid recordings are visibly unavailable and never produce a failure grade.

An encounter captures the complete JSON package, audio hash, optional picture bytes, word-article bodies and shuffled option order.
Initial playback precedes all questions; all three answers precede feedback. Wrong main point, fewer than two correct or give-up
is Again; two including the main point is Hard; all three is Good unless extra replay, extra slowing or declared guessing caps it
at Hard. The first complete replay is normal. There is no Easy or response-time grade. Only Listening uses a 24-hour Again step;
first success keeps the existing one-day multiple-choice cap. Submission atomically writes one state, canonical log and immutable
response snapshot. Subsequent feedback replay cannot rewrite the submitted assistance or answers.

Room v13 keeps durable exposure independently from SRS. Opening neutral context is not exposure; actual playback or revealing
study text is. Study and discarding do not debit introductions or invent reviews. After unscored study/discard, fresh scored entry
waits 24 hours after the last exposure; stored FSRS due dates remain untouched. Resuming the same encounter preserves first-encounter
status, assistance and position. First encounter means first in that content source and is labeled separately from familiar-passage
results; neither demonstrates unseen-listening proficiency.
The overview separates cooldowns from content/audio problems and explains the shared new-item allowance independently from listening
time. Cooldown deadlines remain exact instants for eligibility; the UI shows local, locale-formatted date/time rounded up to the next
minute, without seconds, so a displayed deadline never promises an early unlock.

Full/light/rest days permit at most 2/1/0 new passages and 300/150/0 estimated seconds, within the shared new-item budget.
The captured estimate is twice the recording duration plus 90 seconds, charged once on the first recorded review, including Again.
Listening backlog reduces new time by `clamp(2 - actionableDueSeconds / 300, 0, 1)`; cooldown/media-blocked passages do not count.
Listening rows are excluded from other modes' count-based capacity/backlog calculation but contribute to shared introductions and
study-day history. Due reviews are not time-capped. An unfinished new encounter reserves one shared unit. One encounter per source
must be resumed or discarded before another starts. Previously permitted submissions remain valid after midnight/settings changes,
with their debit on the submission date; reservations are not reviews and are released on submission/discard.

The platform `MediaPlayer` reads verified in-memory mono Opus, requests transient speech audio focus, pauses on focus loss,
headphone unplugging or backgrounding, and never automatically resumes. Deliberate extra slowdown preserves pitch. Full completion,
not a timer or a successful header check, unlocks questions; decoder errors block submission without changing SRS.
Interruption also invalidates a pending seek/start. Successful full playback can clear a transient player-error block offline, but
only after rechecking the current approved package and cached audio; it cannot approve replacement drafts or repair corrupt bytes.
`listening:<encounter UUID>` routes deliberately survive process recreation, unlike ephemeral quiz routes.
Source changes do not retarget an encounter. In-use audio remains pinned through content replacement; unused old audio is pruned.
Export v5 adds source-bound encounters, exposure, captured content and immutable responses without binary media. The grammar
analyzer accepts the new member but does not interpret listening responses as grammar evidence.

## 5. Words, levels and advancement

- Word stacks are cumulative in *membership* (`words-n4` includes N5 words) but keyed independently in SRS state, so switching from `words-n5`
  to `words-n4` starts those words afresh. Never share state across stacks.
- **Onomatopoeia stack** (`words-onomatopoeia`, a `WordStack` with `tag = "onomatopoeia"`): membership is the word's tag alone, independent of
  JLPT level or kanji, so it covers unlevelled kana-only words that appear otherwise only in "All words". It is offered whenever at least
  `MIN_TAG_STACK_WORDS` (4) tagged words exist, even before N4 is unlocked, so a quiz always has distractors. It uses the normal word quiz
  directions, its own SRS state (a word studied here and in "All words" has two schedules, as for N5/N4) and the shared daily new-card
  allowance. Distractors come only from the stack's own words, so options are all onomatopoeia. `offeredStacks()` keeps home, quizzes and
  reminders in agreement; an unavailable saved stack falls back to N5. The N4 advancement offer needs the N5 word states, which exist only for
  the selected stack, so `shouldOfferN4` makes no offer while any other stack (including this one) is selected.
- **Named scenes and picture practice** (#45): optional `scenes/<word>.json` catalogs declare stable word-scoped scene IDs, taught-sense
  labels, English descriptions, image paths and explicit `quizEligible` booleans. Scene JSON uses a separate LF-normalized manifest
  `scenes` map; images use the raw-byte-hashed `images` map. Both contribute to the content version without creating errors on older
  clients that ignore the new map. Only an absent catalog synthesizes an eligible legacy `image`/`image_scene` scene. A declared but
  rejected catalog cannot fall back around its restrictions; lexical content and any independently valid legacy article picture remain.
  A retained legacy fallback must match an eligible named scene and its description, since older apps cannot understand article-only flags.
- **Media integrity and storage:** the pure parser checks flat paths, hashes, size, complete WebP envelopes and one owning word per
  image. The Android importer additionally decodes with `SceneImages`; no bitmap may exceed 2048 pixels on either side, and decoded
  display images are sampled down to at most 512 pixels per side. Failed assets remove only their variants, with reported problems.
  Room v10 adds `word_scenes` and `words.quizImage` (the first eligible representative, independent from the article header).
  Its migration seeds legacy scenes from cached bytes without resetting content, sync metadata, schedules or Grammar focus/history.
  Source-scoped scene parser epoch 1 is recorded only after transactional replacement; it refreshes previously ignored metadata even
  at an unchanged revision. Images, scene metadata and words are replaced together and removed together for that source.
- **Both directions:** Picture → word retains `WordDirection.Picture`, `StudyMode.WordPicture` and route `pc`. Word → picture uses
  `WordToPicture` and `wp`. Both use the existing JpToEn gate, shared new-item allowance, stack membership, padding ownership and
  practice suggestions. Each direction keeps its own **word-level** schedule; scene IDs never become SRS keys. Target variants vary
  across sessions but remain fixed for an in-session retry. The shared option selector retains title/form deduplication, curated
  distractors and symmetric non-transitive exclusions. Exact duplicate image bytes cannot form opposing picture choices; words
  sharing an identical target variant are also withheld rather than treated as new lexical synonyms.
- **Ready before grading:** a session captures word/scene metadata together and decodes every required image before publishing a
  question. Stored image hashes guard against mid-session replacement. Missing, corrupt, changed or unaskable pictures are reported
  and skipped without an answer, SRS log or spent introduction. A question needs at least two permitted choices. Question and answer
  retain the same decoded images; no live image loader can swap or blank a graded prompt. Accessible English scene descriptions are
  available before answering, but Japanese option labels for Word → picture appear only in feedback. Feedback names the selected
  use; articles offer a one-image-at-a-time named-scene gallery, including explicitly marked article-only illustrations.
  Skipping unusable intervening targets must not expose an immediate recorded retry of the same word; defer it behind a different
  remaining item, or omit it when no such question can be asked.
- **Completed #45 collection:** 200 word catalogs cover 395 named illustrations, preserving the original 120 image bytes and adding
  80 missing primaries plus 195 further usage scenes. The reviewed snapshot has 273 quiz-eligible scenes across 194 words and 122
  article-only scenes. These are content counts, not application limits. The content repository's
  `docs/onomatopoeia-scene-images.json` records exact prompts, hashes, revisions and advisory observations separately from owner approval.
  Native-decoder integration checks every declared image in the sibling content clone; real-repository parsing checks every catalog,
  including eligibility and the derived quiz representative.
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

- Drawing keeps the selected brush family (Dot, Chisel, Soft, Ink) from Settings. One subtle randomized look is fixed for each drawing
  session across all cards and answer views: thickness, chisel angle, taper, smoothing, Ink texture/softness, and independent small hue
  (±2–4°) and brightness (±1–5 RGB levels) shifts around deep indigo `#1A237E`. These affect only rendering, never the recorded points or stroke grading.
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
- `WrongShape` carries `faults` (`ShapeDiagnosis.kt`): the drawn stroke, as fitted to the reference box, is compared with its reference for
  position (6+ units), length (ratio < 0.75 or > 1.35), angle (15°+, worded per stroke orientation) and curvature. Position along the stroke's own axis is
  suppressed when the length is off (a shorter stroke shifts its centre). The message then reads e.g. "Stroke 5 is drawn too low"; with no fault it falls back to the generic text.
  Note a pair made from leftover strokes can name the neighbouring stroke (both inner bars of 有 drawn low pair ref 5 with drawn 6).
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
- **Keyboard resizing:** `MainActivity` explicitly requests `adjustResize`; text-entry pages use `Page(ime = true)` so the keyboard
  reduces the scroll viewport while the heading stays visible. Pre-Android 11 needs this mode to deliver edge-to-edge IME insets:
  the default `adjustPan` can move a short, otherwise non-scrollable exercise above the screen without giving it a scroll range.
- Theme follows the system (light/dark); drawing canvases stay paper-white on purpose.
- Kanji, Words, Counters, Grammar and Listening share four rotating home-art sets. Matching default/night drawable names provide
  transparent dark-ink/pale-ink variants; all use the same ArtCard scrims, chevron halo and decorative image semantics.
- **Large screens:** `Page` caps content at 720dp (centred). Cards/Words browsing goes two-pane (list 40%, article 60%, `maxWidth` 1400dp) when `isExpandedWidth()` (>= 840dp); selecting a card shows the article in place, links inside it push a normal doc route. Articles render in a `LazyColumn` (`LazyMarkdownView`; `DocBody`), so a long article composes only visible blocks. A `weight` modifier must go on the `SelectionContainer` wrapper, not its child (parent data is lost otherwise). Checked on the Pixel Tablet AVD only.
- **Strings:** composable `Text("...")` literals live in `res/values/strings.xml` (`stringResource`, `%1$s` args). ViewModel messages, content descriptions and notification text are still literals; there are no translations yet.
- **Rare glyphs:** old components in articles (e.g. 𠯑 in 話) are CJK Ext. A/B/radical characters that phone system fonts often lack. `RareHan` draws
  characters in those ranges from a bundled subset of HanaMin (`res/font/rare_han_a/b.otf`, a few KB, generated by `tools/make_rare_font.py`, notice in
  `third_party/fonts`). `RareHanTest` fails when the content uses a rare character the subset does not cover; regenerate and update its list.
- **Home structure: a short main screen with study-section entries**, including Kanji/Words pages (`HomePages`: routes `home`, `kanji-home`, `words-home`)
  and the Grammar pilot. The main screen shows status, the N4 offer and study entries with summaries; each section exposes its modes. **One button per mode**, never a disabled
  "Start" next to an enabled "Extra practice": `ModeState` (`core/home`) is `Loading | Start(due,new) | PracticeMore | Locked(reason) | Unavailable(reason)`,
  computed from the same eligibility predicates the session view models enforce (a quiz needs two cards, drawing one stroke card, ...) so a
  tappable button never leads to an empty session. The reason for a disabled button is visible text and part of the row's accessibility state.
  Kanji/word counts come from one `HomeData` (`rememberHomeData`), shared by the main screen and both pages, built from strict noise-free queues; a one-minute
  tick makes newly due cards and the day rollover show up. Rows stay in `Loading` until content, review states and introduced-today counts are all known.
  Card browsing lives on each page, since the sets differ: the Kanji page shows the card count and "Browse cards" (route `cards`, `CardSearch`), the
  Words page shows the word count and "Browse words" (route `word-cards`, `WordSearch`: written form, kana in either script, romaji ranked below kana,
  English title words). Both open the card's article; the main screen only notes the source when it is not the default repo.
  After process death ephemeral session routes are dropped from the restored back stack and the user lands on the page they started from.
  Durable listening encounter routes are retained and restore their captured package and playback/answer state.
- **Kana progression:** Hiragana and katakana have independent review state. Yōon cards unlock per script and per mode only when both plain component kana are in Review with at least seven days of FSRS stability; voiced and semi-voiced kana (が, ぱ…) unlock the same way once their unvoiced base (か, は) is in Review with at least three days (`VOICED_UNLOCK_STABILITY_DAYS`; a first Good already gives about 3.2 days, so this is deliberately light, tune it from real use). A card already started in that mode stays available. Locked cards are excluded from that mode's study queues, quiz distractors and new-card counts, and the Kana page reports one total of locked kana per mode. In Kana drawing, a card with no prior Draw review is first shown with the faint numbered reference; this is a practice-only trace, then the card returns later to be drawn from memory without recording the trace attempt.
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

1. Review state is keyed by (sourceId, stack, item, mode, reading), where item is a stable card/exercise ID, never a title or position; the kanji readings modes add the reading match key (`ReadingId`).
   Review-state reads are scoped by source, stack and mode; the new-item allowance is shared across stacks and modes of a source (reminder-history queries are global).
2. A failed sync (fatal error) leaves the previous content intact; the swap is atomic.
3. A hash mismatch or malformed manifest file skips that file only; an unsafe zip entry or limit violation aborts the whole sync.
4. Each quiz grades one designated target. Meaning-quiz word options enforce exact title/form deduplication and curated exclusions; these
   checks do not guarantee semantic uniqueness for uncurated meanings. Reading options have distinct reading keys.
5. Stacks, modes, word directions and reading kinds have independent SRS state; Grammar choose/type are two presentations of one mode and deliberately share exercise state.
6. Grading uses raw/regularised drawn points and the unmodified reference; display jitter and brush smoothing never leak into grading.
7. No network use beyond: GitHub (`api.github.com` head lookup, `codeload.github.com` download), the one-off ML Kit model download, and links the
   user taps (any `https://` link opens the browser; repo links must be exactly `kanji|words|articles/<file>.md`).
8. Room schema changes need a migration; there is no destructive fallback and `exportSchema` is on (schemas in `app/schemas`), so a missing migration crashes.
9. Source files have mixed LF/CRLF: check before multi-line text replacements.

Testing seams: network goes through `ContentHttp` (tests use a fake), view models are built with `serviceViewModel` over in-memory Room, and Compose
UI tests (`ComposeUiTest`) run on Robolectric for the stateless pieces (answer feedback, article toggle, page chrome). Static analysis is Detekt
with a baseline (`config/detekt`); new code must add no findings, and the baseline should only shrink.

### Local progress evidence

Room v8 adds Grammar sets and response details without resetting existing progress; its migration clears only sync metadata to reimport
content. Export v2 includes the canonical log plus `grammar_responses.json`, joined one-to-one by `reviewLogId`; details do not create
additional review events. The stored target construction/relationship describe the exercise, not the wrong option. Typed responses are
stored automatically for recorded reviews, unlike the opt-in drawing log. User-controlled export and Android backup can copy this private
data off the device; see `PRIVACY.md` and `CONTENT-SCHEMA.md`.
Room v9 adds only the focus cursor and nullable accepted-snapshot Grammar diagnostic. The cursor is updated atomically with a first
guided introduction, omitted from progress exports, and included in normal Android database backup.
The local focus readout contains aggregate evidence and decision reasons, never raw typed responses; no new telemetry is added.

Room v11 adds `study_sessions` and nullable `review_log.sessionId`; export v3 adds the sixth member `study_sessions.json`.
Ephemeral study ViewModels use `StudyViewModel` for start/end tracking and ordered review persistence, without changing `QuizSession` or FSRS.
Listening uses its separate durable `ListeningStudy` orchestration and records the same canonical session/review tables.
The route registry closes sessions on actual navigation removal, not composition disposal (article links/rotation).
Completion and early exit store the UI answer totals, including practice-only confirmations; process death leaves an unknown ending.
Historical answers remain unassigned. See `CONTENT-SCHEMA.md` for linkage, mixed readings/padding and timestamp limitations.

Room v12 adds nullable `grammar_responses.choiceSnapshot`. Export v4 includes its structured versioned choose-any policy, actual ordered
options and correct IDs for each newly recorded choice. Legacy/typed rows remain null. This is interpretation context for the same
single canonical answer, not additional reviews; neither old presentations nor per-answer source revisions are reconstructed.
The local analyzer accepts v4 and preserves v3 session validation and existing target-level metrics without exposing option prose.

The stdlib-only `tools/analyze_grammar_progress.py` gives two overlapping post-hoc views of those events (recorded construction and
relationship IDs), with choice/type and first-observed/repeated strata. Retain both for diagnosing where contextual contrasts need attention,
not as separate mastery measures or schedules. A repeated set-local ID may conflate labels; functional answer classes do not measure each
member form. Hard and empty-response counts are available, but there is no separate guessed flag and successful confirmations are absent.
Cached sync SHAs are only export-time snapshot hints, not historical answer revisions. Small author/reviewer samples, repeats, presentation
differences and visible English prevent an efficacy claim. Existing evidence does not justify another question type or broader coverage
within #46; scope those separately if a concrete need emerges. No real export, typed response or private readout belongs in the repository.

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
- **Portability**: progress ZIP export is available; import/restore is not implemented, and iOS is out of scope.
- **Grammar**: #48's [phased contract](GRAMMAR-INTRODUCTIONS.md) implements cumulative JLPT scope, focused introductions and relationship-aware
  new-material selection. The conservative pilot heuristics, incomplete classifications and editorial links are not a validated curriculum.
  #47 adds occasional choose-any two-correct Grammar presentations. Counter applicability and select-all interaction remain out of scope;
  learning effectiveness remains unestablished.
- **Release**: Play listing, data-safety form, check the image model's output terms for the icon (`RELEASE.md`).
