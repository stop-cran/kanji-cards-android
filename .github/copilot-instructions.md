# Ensō Nihongo Android – working notes

Kotlin + Jetpack Compose app that studies kanji from a user-configurable GitHub content repo (default `stop-cran/learning-japanese`).
Design decisions, principles, invariants and evolution: `docs/DESIGN.md` (read first; update it when a decision changes). Plan: `docs/PLAN.md`. Content format: `docs/CONTENT-SCHEMA.md` and the content repo's `docs/content-format.md`.
Keep this file current: add a short note whenever you hit a gotcha, pitfall or non-obvious decision.

## Layout

The pure `core` packages live in the Kotlin/JVM `:core` module; Android `data` and `ui` packages remain in `:app`.
Production package names are unchanged. Shared JVM test builders/media live in `core/src/testFixtures`.
The current module inventory lives in `settings.gradle.kts`; build tasks and integration setup live in `.github/workflows/ci.yml`.

- `core/` – pure Kotlin, no Android imports, unit-tested on the JVM. Packages: `content` (front matter, parser, safe zip, repo URL, search), `srs` (FSRS-5, queue builder, stacks, pacing, font policy, reminders),
  `quiz` (option builder, `QuizSession`), `words` (word queues/stacks/search), `kana` (built-in kana catalog and progression), `draw` (stroke matcher, grader, regularisation, brush geometry), `markdown` (own parser).
  Put new logic here whenever possible.
- `data/` – Room entities/DAOs (`Database.kt`) and migrations, learner `Settings` and legacy `SyncPreferences` bookkeeping
  (same SharedPreferences persistence), `ContentSync` (network through `ContentHttp`), workers and `DrawingLog`.
- `ui/` – Compose screens and view models. `MainActivity` owns navigation (see Gotchas).
- `KanjiApp` is the composition root (`db`, `settings`, `contentSync`, application-lifetime `syncCoordinator`, `services`).
  Factories provide dependencies; legacy ViewModels still receive `Services`, while Counter/session persistence uses narrow stores.
  Home loading/projection lives in `HomeRepository`/`HomeStateProducer`; `HomeRoute` supplies state/actions to scoped presentation.

## Build and test (Windows)

- Needs `JAVA_HOME` = JDK 17 and `ANDROID_HOME` (see `docs/PLAN.md`). Android Studio's Gradle JVM must be JDK 17 (this Gradle version rejects newer JDKs).
- `.\gradlew.bat detekt :core:test testDebugUnitTest assembleDebug lintDebug assembleRelease` matches CI; lint alone does not run Detekt.
  `.\gradlew.bat -PcoreOnly :core:check` excludes Android project configuration and needs only JDK 17, not the SDK.
  `RealContentTest.parsesRealContentRepoWhenPresent` reads `../../learning-japanese` if that
  clone sits next to this repo, and is skipped otherwise.
- Emulator (Pixel 9, API 35), adb driving, screenshots and copying an APK to the phone: see the `android-emulator` skill in `.github/skills/`.
- Gradle treats `RealContentTest.parsesRealContentRepoWhenPresent` as up to date when only the sibling content clone changed; re-run it with
  `.\gradlew.bat :core:cleanTest :core:test cleanTestDebugUnitTest testDebugUnitTest --tests '*ContentTest*'` after pulling or editing the sibling
  content checkout (`..\learning-japanese` from the repository root, `..\..\learning-japanese` from `app` or `core`).
- Check the exit status/test results before committing; a chained `test; commit; push` publishes a red build.
- Native `OK (N tests)` can include `assumeTrue` skips. Use raw instrumentation output (`-w -r`) and require a
  status-code 0 for each selected case; provide the external acceptance archives those cases require.
- IME visibility can precede the end of its native inset animation. Await that completion before scrolling/tapping in keyboard
  reachability tests; Compose idleness alone does not wait for the platform animation.

## Work flow

One GitHub issue per work item (file it, get an independent review of the plan, post the outcome as a comment), then implement, run the
unit tests, check the UI on the emulator, update `docs/DESIGN.md`, commit with the Co-authored-by trailer, push, close the issue with the
commit. Say plainly what was not tested on a real device. Release build: `assembleRelease`.

Code review: use read-only reviewers on the uncommitted diff, one per feature so each has a bounded scope, and give each the intent plus
the neighbouring implementation to compare against. Adjudicate findings yourself: a reviewer's claim about direction or sign semantics
(left/right, clockwise, up/down on a y-down screen) must be checked against a concrete example before acting. If the claim is only
ambiguous wording, reword the user-facing text instead of flipping the logic.

## Gotchas

- **Edge-to-edge on API 35:** content draws under the status/navigation bars; a button there is visible but untouchable. Every screen goes
  through `ui/Page`, which applies `statusBarsPadding`/`navigationBarsPadding` and a scroll container. Don't build screens without it.
- **Keyboard on older Android:** keep `MainActivity`'s `adjustResize` and use `Page(ime = true)` for text entry. Pre-Android 11 needs
  resize mode for edge-to-edge IME insets; default panning clips short exercises without creating a scroll range. Search pickers with
  bounded nested lists need it too: check that the final search result is selectable without dismissing the keyboard. Check API 29 and 35.
- **System back:** `BackHandler` in `AppNav` pops the route stack; without it back closes the app.
- Stateful Back callbacks must be lambdas, not references to local functions capturing snapshots.
  Function-reference equality can hide changed captures from `rememberUpdatedState`, retaining a null loading row.
  Exercise Android key dispatch after asynchronous loading, feedback and nested article navigation.
- **Screen state across navigation:** `AppNav` composes only the top route, wrapped in `SaveableStateProvider` keyed by typed entry ID
  or legacy stack position/route (logical target for consolidated Grammar details); `pop()` removes the state. Without the holder, search/scroll is
  lost when another screen is pushed. Lists that must keep scroll use an explicit `rememberLazyListState()`.
- An asynchronous screen's short loading body can clamp a restored `ScrollState` to zero before real content arrives.
  Keep the same `Page` call but disable its scrolling while loading; test a measured loading frame on Back and process restoration,
  not only an isolated diagram/list saver. A conditional Ready subtree also unregisters its saveable values when it disappears:
  give its non-consent browser/evidence state a holder that survives loading, and keep exception consent outside that saved subtree.
  Exercise a rendered Start-loading frame before both stale-preview and fresh-session returns, with nonzero pan/list offsets.
- **ViewModel scope:** typed Draw/document entries provide route-owned stores/lifecycles, retained across coverage/rotation and cleared
  on removal. Other legacy families still use Activity-scoped, route-keyed VMs; their fresh session keys and explicit leave callbacks
  remain required until migrated. `AppDestination` is the typed/legacy adapter; do not add another authoritative back stack.
- **Live routing:** `MainActivity.kt` owns route dispatch and restoration; feature route helpers define argument contracts.
  Use those implementations rather than a duplicated route roster. `docs/DESIGN.md` owns the behavioral rationale.
- Source-bound docs encode source/path and optional captured Grammar topic separately. Never resolve a session's reference context
  from later settings/content. Contents jumps use real lazy indices, including leading items, and must not rerun on nested Back.
- **Manifest hashes** are computed over LF-normalised bytes (`tools/build_manifest.py` in the content repo and `ContentParser.sha256`). Keep
  both in sync, otherwise files fail with "hash mismatch" on Windows checkouts with CRLF.
- Named word scenes use the separate manifest `scenes` map, never `files` (older clients discard that folder). A declared bad catalog
  disables quiz eligibility rather than synthesizing a legacy fallback. Graded image questions use predecoded, hash-checked snapshots;
  `DocRoute` owns live article image loading; `WordImage` only renders its result. Scene IDs must never become SRS keys.
- Image cache rows use the basename (`name.webp`), while article references use `images/name.webp`; native gallery fixtures must
  match that key and wait for the asynchronously decoded image, not merely for surrounding article text.
- Markdown links are file-relative: a word links to `../articles/name.md`, not `articles/name.md`, which is rejected as a nested path.
- Word reading help uses a per-run transactional word/scene/kanji snapshot and the selected vocabulary deck's cumulative level.
  Japanese choices already show kana: highlight only, never nest a help action in an answer button. Reading/typing get no answer-revealing aid.
- Word Reading requires an ideograph in the written word, not just different spelling/reading or nonempty kanji metadata.
  Use the shared code-point-aware `hasReadingToLearn` filter for all admission paths; retain excluded kana-only words' saved progress.
- Word unlock counts reuse `WordQueues.eligible`, not queue length (which is budget/due constrained). Review phase, repetition count
  and stability all matter; started modes remain eligible. Candidate-pool size is not the unlocked-target count.
- Large word text must not inherit body line height: use proportional spacing and renderer-based fitting, with readable wrapping at the
  size floor. Preserve annotated reading links and test their bold metrics/clicks under narrow widths and enlarged text.
- Level chips use bare N5-N1/All labels; explain cumulative versus exact scope outside the shared `LevelFilter` row.
  Never infer membership or saved identity from a chip's text or position; concise labels do not unify scope semantics.
- Native browser tests must reveal the outer Page's chip row/list before scrolling the inner viewport.
  Returning from a vocabulary article restores its lookup dialog; close that dialog before testing question controls.
- Candidate Grammar chapters can span several article sets. Derive expected membership from the catalog and authored groups;
  select the exercised group from the requested set. Seed a disclosed due item on the disposable source when a linked alternative
  must appear first; shuffled fresh intake is not a deterministic coverage guarantee.
- Built-in Kana intake counts first recorded practice per script/kana/mode, matching its per-mode new queues.
  Keep this exception source-scoped; other sections retain their existing item/reading accounting.
- Daily-count copy must distinguish the Kana pool from the selected-repository pool, and allowance ceilings from eligible opportunities.
  Listening's extra introduction/time ceilings are not a third pool; unintroduced scored encounters can reserve repository slots before answers.
- **Content is untrusted.** Parse defensively, skip bad files instead of failing the sync, never render raw HTML, cap archive sizes.
- First-import readiness is separate from completed empty DB observations. Gate repository entry UI, not captured
  Listening recovery; test actual section controls and both cache-loading and section-projection restoration frames.
- Home calculations capture source/branch, selections, preferences, one Clock instant and its zone. Keep heavy projection outside
  composition; ticks must update allowances, local-day/week calculations and N4 dismissal expiry. Unexpected failures propagate.
  Focus cutoff initialization is a repository-owned write, not pure presentation. Do not revive cancelled selections.
- Submit Settings/launch/Home sync through the application coordinator, not screen/Activity jobs. Preserve forced versus ordinary
  versus throttled launch semantics, and do not coalesce across an intervening branch replacement. Periodic work keeps its own
  execution-time source and cancellation. Shared diagnostics remain source/branch-bound, including queued and interrupted work.
  Save local repository validation errors with the form, so rotation cannot replace an invalid saved URL's error with old sync success.
  Keep failure retryability in saved status without treating every non-retryable result as permanent or disabling manual sync.
  Catch operational failures around launch preflight as well as import; one failed request must not shut down the queue.
  Engine activity revisions are per-source observation identity: equal results from a later periodic attempt must supersede an old
  coordinator fallback even if the observer never sees the intervening Running state. Cancellation still propagates.
- N2 nearly exhausted entry headroom while byte budgets still passed. Count directories/skipped ZIP entries and coordinate the content
  publishing guard with `SafeZip` (see `docs/DESIGN.md`); more entry capacity does not relax byte limits or upgrade installed APKs.
  Complete-archive integration fixtures must honor Git `export-ignore` too, rather than zip contributor evidence that never ships.
- Sync diagnostics are not rejected-card counts: optional metadata warnings keep their owning cards.
  Report distinct rejected files with explicit units, and count retained Listening catalogs from the accepted cache, excluding unsupported placeholders.
- Counter reading metadata belongs to the displayed canonical phrase, not every counter in legacy `kana`.
  Whole-expression modes use narrow authored-answer matching, never legacy numeral arithmetic (十十 must not become 二十).
  Keep v2 files separate from v1, advance the source-scoped counter parser epoch for newly retained metadata,
  and distinguish the shared allowance from summed per-mode new opportunities.
  Intersect counter level and group against the source once; sequential source-restoring filters can silently undo
  the first restriction. Retain foreign reverse-choice references without adding them to scoped queues.
  Native cumulative-scope fixtures must retain easier content and isolate target-level questions with test-only due states.
  Mount the real CountersPage to verify chip-to-route entry; a direct quiz mount cannot prove that handoff.
  For target-level extra continuation, await the persisted session end marker, then make a reviewed target due in the test DB:
  upcoming items rank by relative urgency with noise, so far-future easier cards can still precede freshly reviewed targets.
  Native home-entry tests must await the matching rendered mode action as well as refreshed counts; a count-only wait
  followed by a one-shot action lookup intermittently missed the N3 reverse Start action on API 35.
  Typed native fixtures must mirror edge-to-edge/adjustResize, await a visible settled IME, and measure both controls above it.
  Compose idleness and a keyboard-named screenshot do not prove this; at 200% font, wait for the same question's feedback
  only after a real control touch, not an immediate off-thread ViewModel cast.
- Grammar additional correct choices are authored, not derived from typed spellings. Use the captured presentation for grading,
  both correct highlights, rationales and response export; bind callbacks to its instance because different exercises can reuse IDs.
  Advance `ContentParser.GRAMMAR_PARSER_EPOCH` for newly retained Grammar metadata, not the historical level/group readiness thresholds.
- Grammar blanks can hold a whole sentence or response label, not only a construction fragment. Keep global answer instructions
  neutral; #74 fixed a native prohibition that contradicted valid whole-sentence tasks. The existing typing cap counts 80 UTF-16 units.
- Grammar's Page key uses immutable question/answer state, not the typed draft, so checking resets to the outcome without resetting
  scrolling on each keystroke. Keep that key inside the session's saveable holder to retain the feedback offset on article Back.
- Grammar roadmap catalog errors stay separate from `grammarProblem`: a navigation-only failure must not block valid lessons.
  An authored group without a chapter is not an unowned compatibility group. A valid leaf DAG can collapse into a chapter cycle;
  validate both graphs without deleting true prerequisites or inventing an "Ungrouped" prerequisite chapter.
- Roadmap diagrams measure a finite viewport and cull cards, not a world-sized layout that can overflow Compose constraints on
  large forks. Keep placement independent of progress/filter state, and preserve unknown level counts as unknown until sync.
  Adjacent two-axis `scrollable` modifiers install their own accessibility actions: put the combined scroll semantics before
  them, and exercise both `ScrollBy` and `ScrollByOffset` as well as touch drags and restoration. Touch-only checks missed this.
- Targeted Grammar practice must use `targetedNew`, not the whole-group guided lock or ungated ordinary `extraIds`.
  Compare complete accepted previews at Start, not only a repository version: changed questions, membership or restrictions expire consent.
  Targeted continuation and process recovery retain the logical source/branch/target but create a fresh route and unapproved preview.
  Validate cached exercises before building virtual compatibility groups, which assume valid construction/relationship references.
- A stable details key is not a session identity. Replacing a targeted batch must cancel the old registered ViewModel, create a
  fresh UUID, and key exception consent separately from preserved page/list/pan state. `rememberSaveable` inputs alone do not
  validate restored consent across process recreation. Exercise both unchanged rotation and a fresh route with identical membership.
  Preview refresh success and error paths must both check that the pending preview is still current before changing UI state.
- When splitting a Compose screen into phase-specific children, the parent's phase discriminator must itself be observable.
  A child observing `ui` does not recompose a parent reading a plain approval field. Test that the real host displays the first
  question after Start, not only that the ViewModel reached a question; the native Grammar flow regression covers this boundary.
- Grammar readiness must agree between live recording and replay of identical canonical history, including equal timestamps.
  Reconcile only provisional grants in that timestamp batch, never earlier milestones; test by deleting derived rows and rebuilding.
  Keep bounded backfill page reads/calculation/writes atomic against live answers; mapping checks alone cannot detect changed evidence.
  Reconcile a delayed timestamp from its original evidence window, not a moving "now minus 21 days" window that can erase valid proof.
  New prerequisite publications use `foundationRequirements`, not legacy `requires`: additive chapters alone do not negotiate old-client gate semantics.
- The real content-import test must independently project additive `foundationRequirements` into expected cached groups and check
  the declared curriculum/leaf-edge provenance. Plain `GrammarSet` deserialization intentionally ignores that transport field;
  comparing it unchanged with imported groups worked only while the real corpus had no new gates.
- **Room:** list columns are joined with `SEP` (`\u001F`); use `joinSep()`/`splitSep()`. `review_log.id` is auto-generated, so pass `0`.
  Review state is keyed by (`sourceId` = lower-case `owner/repo`, stack, item, mode). Never key it by title or position; titles are editable.
- **Listening:** persist exposure separately from SRS and retain the captured package/assistance through process recreation.
  Scored Practice more bypasses daily plans, never exposure cooldowns/media checks; reserve extra unseen passages even outside the normal fresh list.
  Only a completed full playback unlocks questions. Technical errors and unscored study never manufacture failure reviews.
  `responseJson` is immutable after submission; feedback playback updates only encounter state/exposure.
  Focus/headphone interruptions must invalidate pending starts, not just active playback. Recover transient media blocks only after
  full playback and current approval/hash verification; invalid cache discovered during start must be blocked outside its rolled-back transaction.
  Key the listening page by phase so answer feedback starts at the result rather than retaining the questions' scroll offset.
  Export-version changes must update `tools/analyze_grammar_progress.py`, not just Kotlin export tests.
  The legacy importer validates every `audio` entry, even unreferenced ones; short new-task clips in that map can
  abort the entire sync when listening is cached. Keep new documents and media in separate namespaces; see the
  adopted `docs/LISTENING-TASKS.md` contract. An unknown task is not corrupt legacy content.
  B's task import has its own Room-stored accepted revision/parser epoch, independent of failed attempts and ordinary sync.
  Do not enable a task renderer merely because its package parses. Shared Listening admission/allowance runs transactionally
  across catalogs; a familiar but never-introduced skill still reserves against other modes. Preserve every owner media pin.
  N4 tasks require schema 2, not relabeled schema 1. Apply source-saved level and browser-format eligibility before
  bounded fresh selection; filtering an already truncated global list starves visible candidates. Keep actionable
  backlog/reservations source-wide, revalidate start scope and never retarget or hide an existing capture.
  Native start acknowledgement must survive coroutine cancellation; clearing a failed stage requires that stage's completion,
  not any successful clip. A partly heard whole replay becomes assistance if abandoned or submitted before completion.
  Consume replay allowance/requested audio assistance only at actual start, not preparation. Persist picture failures before
  rethrowing; only revalidating that captured picture clears its block, independently of audio-stage recovery.
  Cancelling an unstarted seek must retain restart provenance when its position was reset; ordinary resume must not erase that help.
  API-35 codec instrumentation must foreground an activity: a background-only runner cannot acquire target-35 audio focus.
  On this Windows host, SwiftShader crashed the emulator between native cases; host graphics with Vulkan disabled passed.
  New-task renderers share `ListeningTaskEncounterView`/`ListeningTaskViewModel` and the staged coordinator.
  Record preview exposure from clipped visible bounds, not composition: offscreen choices are not seen evidence.
  Commit visible exposure outside the playback-action mutex; a held generic instruction must not delay that durable write.
  Technical-error controls need their own scroll body for landscape/large text; do not leave recovery actions above the scrolling subtree.
  Keep phase-keyed scroll outside article/layout branches; native article Back caught lost nested saved scroll.
  Pause/persist native position before closing the player. A sequence token also stops between-stage continuation.
  Described study is a captured, unscored choice, never an automatic accessibility conversion. Picture accessibility labels
  remain neutral; answer-bearing descriptions belong only to explicit help/feedback/study.
  Check enlarged dialogs with actual system font scaling too; a test-local density override does not scale their separate window.
  Spoken-choice stage headings use captured `optionId` -> authored number, never the stage index or hidden response text.
  Keep three numbered controls auditory until help/feedback. First required choice playback is not targeted replay;
  distinct pulse fixtures test byte/order alignment only, not actual Japanese numbering or listener approval.
  Diagnostic F owns its timeline, not guided grading: omitting a scored item's generic instruction must
  never add fictional completed stages. Preserve responses and cursor atomically; Skip cannot shorten a
  window. Keep section audio focus across silent windows and checkpoint remaining time before restoration
  discards old monotonic deadlines. Feedback replay after process death also needs pending-start recovery.
  Keep timed evidence ahead of auxiliary source/control text so ordinary preview frames do not stall offscreen.
  Key timed evidence by capture and phase, not only its scroll state: unchanged picture geometry may not
  trigger another placement callback. Bind visible callbacks to that rendered capture/generation/phase.
  N4 diagnostics select an exact captured profile, not the cumulative practice scope. Default N4
  freshness excludes scored stories but permits cooled-down examples; retain N5's historical rule.
  Check every eligible N4 timing combination, not one lucky seed. Bind parent/form/result/child level
  and derive UI/export denominators from the capture; omitted new JSON fields still mean historical N5.
  Picture exposure cannot start a worked explanation's clock. Finalized raw results must remain readable
  when media recovery fails; only opening captured feedback needs its media.
  Fixtures and simulated elapsed time never satisfy approved Japanese-media or physical-device acceptance.
- **Quiz semantics:** options are meaning titles and unique by title (one correct answer). Wrong = Again (re-asked after 3 cards),
  right = Good, "I guessed" = Hard. Time-based Easy was dropped: with 4 options a fast answer is often luck.
- Keep Next neutral: feedback precedes retry insertion, and a final item can omit its retry.
  Kana guessing passes `weak`; ordinary Word/Grammar guessing does not itself request reinsertion. Trace-memory steps can also be deferred.
- **Daily new-card budget** counts kanji whose first review log entry is today (`newCardsIntroducedSince`), per mode.
- **FSRS:** weights are the FSRS-5 defaults typed from memory; verify against the official table before relying on exact intervals. Sub-day
  reviews use the short-term stability formula, so same-session re-asks don't inflate stability.
- Compose in a `Column` scope: `Modifier.weight` only works in the matching `Row`/`Column` receiver; `Page`'s content lambda is a `ColumnScope`.

## Tooling gotchas (Windows / PowerShell)

- Run Gradle with `$env:JAVA_HOME` set explicitly; new shells don't inherit it.
- Non-ASCII output from Python/PowerShell needs `$env:PYTHONIOENCODING='utf-8'`.
- `uiautomator dump` can print `ERROR: null root node returned by UiTestAutomationBridge` yet exit successfully and leave old XML behind.
  Require its `UI hierchary dumped to:` success line before trusting/pulling the hierarchy; let the window settle and retry after a failed dump.
- The file `create` tool refuses existing files and missing parent directories; create folders first.

## Markdown viewer and extra practice
- `core/markdown` is a small own parser (no library, no HTML). `Links.resolve` only allows `https://` and repo-relative `.md` links into `kanji/`, `words/`, `articles/`; anything else stays plain text. Content is untrusted.
- `ui/MarkdownView` renders blocks; doc links push a `doc:<path>` route (`DocScreen`), so Back returns to the quiz exactly where it was (quiz ViewModel is keyed by its route and survives).
- Do not call composable helpers from non-composable lambdas (table rows are a separate `@Composable`).
- Minimum table-column widths must also bound the combined physical row width. Keep the navigable column
  windows for unusually wide tables; an unbounded minimum-width sum can overflow Compose constraints.
- Weekly study days observe persisted `review_log` for the selected source plus built-in Kana. Selected-source
  state-map changes alone miss late Kana writes; rebind the observation on source/local-date/time-zone changes.
- Missing `sync_meta` does not mean an empty source: upgrades can retain usable content. First-load summaries
  wait for a source-bound cache observation and use sync activity scoped to the repository and branch.
- Quiz routes: `quiz:<ts>` (due + new) and `quiz:<ts>:extra` (`QueueBuilder.extra`, max(10, batch size) cards: overdue, unseen, soonest due; a short session is padded by `PracticePadding` with weak learned cards of other stacks, which are recorded in their owner stack via the view model's `owners` map, never the selected one). The ViewModel starts via `ensureStarted(extra)` from `LaunchedEffect`, never in `init`.

- Downloadable Google Fonts (`ui-text-google-fonts`) do NOT work for CJK: the provider serves only the Latin subset, and Compose falls back to the system face silently. Bundle subsetted fonts instead (see README "Fonts").

## Stroke matcher (`core/draw`)
- `StrokeMatcher.match(reference, drawn)` is pure Kotlin. It normalises the drawing onto the reference box (per-axis scale, refit on matched strokes), links drawn to reference strokes greedily by mean resampled distance (single, joined, broken candidates), then reports `Reversed`, `WrongOrder` (strokes outside the longest increasing run of reference indexes), `WrongShape`, `Joined`, `Broken`, `Missing`, `Extra`.
- Tolerances are in `MatcherConfig` (fractions of the 109 box). They were tuned only on SIMULATED hands (`StrokeMatcherTest.Hand`), so re-tune with real recorded drawings from a device before trusting them; add them as fixtures.
- `WrongShape` issues carry `faults` from `ShapeDiagnosis` (position, length, angle, curvature) so `describe()` can say e.g. "too low". Describe a position/lean by an unambiguous reference (a lean is named by where the stroke's top is). When two neighbouring strokes are both off, the leftover pairing can name the neighbour; verify against the exported drawing before changing a threshold.
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
- Opt-in drawing log (Settings, off by default): DrawingLog writes one JSON per checked drawing to app-private files/drawings/ (strokes as [x,y,t] canvas px, outcome, issues, ML candidates). Never uploaded. Pull from a debug build: `adb shell run-as io.github.stopcran.kanji cat files/drawings/<file>.json`. Turn these into matcher fixtures. Play data-safety: local-only, nothing collected.
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
- Kana yōon unlock is per script and mode: both plain components must be in Review at 7+ days of stability; voiced/semi-voiced kana (`KanaCatalog.voicedBase`) need their unvoiced base at 3+ days. Preserve already-started cards and apply the same eligibility filter to queues, quiz distractors, and new-card counts.
- Kana drawing introduces a never-reviewed card with the shared faint numbered reference once per session, then defers it for a from-memory attempt. The trace attempt must not enter SRS history.
- Only omitted word `jlpt` enables fallback: present values must be top-level unquoted integers 1-5. Preserve raw front-matter entries to reject blank/quoted/list values and duplicate or multiline shadowing without changing kanji parsing. Never synthesize level tags; word tags and quiz tag scoring stay independent of `jlpt`.
- `DatabaseMigrationTest` runs Room upgrades/reopen tests on Robolectric native SQLite (API 28), without the content repo or an emulator. Room 2.6 databases are not `Closeable`; close them explicitly in `finally` rather than using Kotlin `use`.
- In view-model tests, a DAO read or a barrier on the underlying executor does not drain Room's separately serialized transaction queue.
  With `UnconfinedTestDispatcher`, enqueue an empty `db.withTransaction {}` after advancing the answer and await it before asserting
  recorded events or their absence (see `GrammarQuizViewModelTest`). This also covers unrecorded successful confirmations without sleeps.
