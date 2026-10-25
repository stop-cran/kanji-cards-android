# Japanese listening tasks: contract 1

The adopted [N4 extension](LISTENING-N4.md) separately specifies schema-2 support,
level scopes, bounded capacity and an N4 diagnostic profile. Its implementation/media
profile/media work is pending; B's schema/scoped-practice/capacity support is implemented.
The N5 learning behavior and acceptance boundaries below remain unchanged.

Design deliverable for [A / #66](https://github.com/stop-cran/kanji-cards-android/issues/66).
**Independently reviewed implementation contract, adopted under delegated autonomous decision-making.**
Installment B supplies the shared importer, persistence, accounting and staged-player foundation;
C supplies the common guided host and focused renderers; D adds the two spoken-choice renderers.
Approved runtime content and the assessment wrapper remain E/F deliverables.
Implementation order is B (shared infrastructure), C (focused tasks/common host), D (spoken choices),
E (approved original content), F (assessment). The review of A's issue plan did not approve these decisions.
The concrete design was subsequently reviewed and its reservation finding resolved. The conservative
defaults below are adopted under delegated autonomous implementation authority, not recorded as
separate personal confirmation. This does not grant paid-media approval
or waive E/F's actual-listening and device gates.

**Owner-testing publication, 2026-10-25:** The owner subsequently explicitly authorized
the 32 selected E packages for personal testing before remaining physical-phone/full-section
acceptance. The [content policy](https://github.com/stop-cran/learning-japanese/blob/main/docs/listening-tasks.md#owner-authorized-testing-publication)
records this bounded exception and exact-output provenance. Schema-1 approval remains runtime
permission; the approval note identifies testing scope, not completed device certification.
No app parser, learning policy, unsupported-item handling or integrity limit is relaxed.
General-release E/F acceptance remains separately tracked.

Rebased on app `740b7da` and content `73bde0f`, including the 36 existing English-question passages.
Their schema, identities, aggregate grades, normal replay and history remain unchanged.
The [content-side schema](https://github.com/stop-cran/learning-japanese/blob/main/docs/listening-tasks.md)
is normative for packages; this document owns runtime policy. Both use contract/policy version 1.

## 1. Evidence and four separate task types

The official [purposes](https://www.jlpt.jp/e/guideline/pdf/n5_e_revised.pdf),
[2018 booklet](https://www.jlpt.jp/samples/sample2018/pdf/N5L.pdf) and
[script](https://www.jlpt.jp/samples/sample2018/pdf/N5script.pdf) distinguish:

| Task discriminator | Listening objective | Observed 2018 presentation | Contract-1 presentation |
| --- | --- | --- | --- |
| `task-based` | Extract the information needed to decide an appropriate action | Question, conversation/announcement, repeated question; four printed text or visual choices | Spoken focus before passage and repeated afterwards; four authored, fixed-order Japanese text or numbered visual choices |
| `key-point` | Attend to a previously specified point in a conversation/announcement | Same question-before-passage instruction as task-based; four printed text or visual choices | Same stage topology, different explicitly authored listening objective; never infer subtype from number of speakers |
| `verbal-expression` | Select what the indicated person should say in the depicted situation | Required illustration/role marker; spoken situation/question and three numbered spoken expressions | Keep the illustration visible; play situation then all three numbered choices; answer buttons reveal numbers only |
| `quick-response` | Select a fitting response to a short utterance | No task illustration; utterance and three numbered spoken responses | Play utterance then all three numbered choices; no written utterance/response before permitted help or feedback |

Booklet PDF pages 3/8 give the first two instructions; pages 4/9 show map/visual and text choices;
pages 12-15 show the expression illustrations; page 16 describes quick response without pictures.
The first two types do **not** acquire an invented N3-style reading-window distinction.
Separate fixtures must preserve their objectives even where code and stage order agree.
All new option orders are authored and fixed, including numbered maps. Legacy options still shuffle.
Task objectives are author-assigned, not mutually exclusive sentence templates.

The [source record](https://github.com/stop-cran/learning-japanese/blob/main/docs/listening-task-sources.json)
identifies exact PDFs/recordings, inspected pages and limits. The four recordings were fully decoded;
bounded opening excerpts were machine-transcribed locally and compared with the booklet/script.
This is recording inspection, **not human listening approval or pronunciation certification**.
ASR made lexical errors; it is not a replacement for the published script or E's actual listener.
The workbook contains 7/6/5/6 scored items and worked examples. This is an observed 2018 sample,
not a guarantee of current/future official item counts. Its decode-derived duration is about 29 minutes.
Official [timing guidance](https://www.jlpt.jp/e/guideline/testsections.html) says approximately 30 minutes
and permits variation with the recording. Publish no official question, picture, script or recording.

## 2. Identity, exposure and snapshots

Every new scored item has one source-scoped schedule:
`(sourceId, "listening", task.id, "Listening", "")`. One question is one new-task result;
the legacy passage's three questions remain one result. IDs are disjoint across both catalogs.
`sourceId` remains lower-case `owner/repo`; branches share that source's history, as today.

New tasks explicitly declare `exposureId`, identifying the answer-bearing story/situation.
Legacy default: its unchanged passage ID. Reusing legacy material therefore declares that ID;
different questions about the same story share exposure but have distinct scored IDs only when
they genuinely test a different skill. Equivalent voice renders, numbering or cosmetic changes
keep both IDs. A new ID is not a way to reset mastery or daily accounting. An existing ID cannot
silently change its exposure binding; reject the replacement and require a reviewed correction.

First encounter means no earlier exposure to that identity **in this source**, not absence of SRS.
Snapshot this at the first actual evidence exposure and never recompute it when resuming.
Expose the story when its audio actually starts, required visual/choices are rendered, or
answer-bearing study text is revealed. Generic instructions, neutral titles and capture/preflight
are not exposure. An example has its own story identity; it cannot cue a selected assessment item.

Apply the existing 24-hour scored-entry cooldown across shared exposure identity, including
study, discarded work, feedback and assessment. Resume the existing permitted attempt, not a new
one. Do not rewrite FSRS due dates to implement cooldowns. Merge legacy exposure evidence without
changing historical responses or claiming that unrecorded historical activity is known.

Capture source, branch, repository revision, package and media hashes, task/policy versions,
exposure identity, fixed option IDs/order, word-article bodies, required image, stage state and
assistance. Sync/settings/source changes cannot retarget an open attempt. New starts use the selected
source; an old source's captured attempt can be resumed explicitly with its original source shown.
Pin **every** captured asset through closure, including all assessment items/examples. Historical
answers keep their package/response metadata, but closing releases otherwise unused binary media.

## 3. Guided learning and accounting

Keep one Listening section, labeled task filters and one active listening activity per source across
legacy, new guided/study and assessment. Starting another requires resume or explicit discard.
Share a transactional active-activity guard; separate ViewModel mutexes are insufficient.
Examples are unscored and excluded from normal queues, budgets and assessment denominators.
Before exposure show only the neutral title and task type, not authored topic/level rationale,
file IDs or feedback metadata that could reveal the intended speech act or answer.

The initial pass includes every required stage. Generic instruction replay is not assistance and
does not expose the story. One complete replay of the **whole item evidence sequence**, excluding
the generic instruction, remains normal. A partial/targeted replay, further complete replay,
slowing, revealed question/transcript/translation, or declared guessing is recorded assistance.
The first required plays of three choice clips are never mistaken for three replays.
Guided help may reveal content deliberately; feedback remains hidden until submission.

| Guided outcome | SRS/accounting |
| --- | --- |
| Correct, all required stages completed, no assistance | Good |
| Correct with assistance | Hard |
| Wrong after completed evidence, or explicit give-up after evidence exposure | Again |
| Unfinished, unscored study, unavailable evidence or technical failure | No review; never manufacture Again |
| Explicit discard | Close/release reservation; keep actual exposure; no review |
| Repeated submit/close, feedback playback | No second review/debit and no mutation of submitted response |

Give-up is unavailable during an unresolved technical failure. Selecting an option is not submission.
Use the existing Listening scheduler: Again at 24 hours, first successful introduction capped at
one day, no Easy or response-speed grade. A genuinely new scored skill consumes its own introduction,
even if its story is familiar; familiar exposure is labeled separately, not treated as a free new item.

New-task estimate, captured at admission:
`instruction duration + 2 * sum(non-instruction stage durations) + 45 seconds`.
Repeated-focus stages count at each actual occurrence; shared physical file size is irrelevant to time.
This estimates first pass, one ordinary full replay, answering and feedback; it is not an exam timer.
Keep shared new-item limits and Listening full/light/rest limits at 2/1/0 items and 300/150/0 seconds,
with the existing due-backlog factor. Compute allowance/due workload across both catalogs, not once
per filter. A long item can be unavailable for a new introduction on a light day.

| Event | Reservation, debit and exposure |
| --- | --- |
| Start eligible not-yet-introduced scored ID, including familiar material | Atomically reserve one shared unit; no review/introduction yet |
| Start previously introduced scored ID | No new reservation; normal eligibility still applies |
| First rendered/heard story evidence | Persist first/last exposure before allowing another activity |
| Submit guided task | Atomically write one canonical review, state, immutable response and actual introduction/time debit; release reservation |
| Midnight/settings change while open | Honor captured admission; debit on actual submission date using the established local-day accounting |
| Extra practice | May exceed daily plans, never cooldown/integrity checks; reserve/debit actual introductions exactly once |
| Study, example or diagnostic assessment | No SRS review or introduction/time debit; persist exposure and active-activity ownership |
| Discard/cancel | Release ownership/reservations; preserve already committed responses and actual exposure only |

Admission uses the existing source-scoped scored state (absent/`New` versus already introduced),
not exposure familiarity. After study/assessment and cooldown, a familiar task with no scored
introduction still reserves a unit. Other learning modes must see that reservation until submit
or discard; the one-active-listening guard alone does not protect the shared allowance.

## 4. Playback state and accessibility

Persist `instruction -> evidence stages -> answer -> submitted feedback -> closed`, with a stage
index, playback generation/token, position, required completions, whole-pass count and assistance.
An already completed instruction can be satisfied by the assessment's matching example instruction;
this is an explicit parent-session operation, not arbitrary UI skipping.

| Transition/event | Required behavior |
| --- | --- |
| Prepare/seek/start request | Not completion or exposure; record a token and reject stale callbacks |
| Actual start | Persist stage/evidence exposure where applicable |
| Native completion for current stage/pass | Mark that stage complete and progress; only all required evidence completions unlock answers |
| Header check, timer, seek near end or partial play | Cannot complete a required stage |
| Guided pause/focus loss/headphone removal/background | Cancel pending start/seek, persist position, release focus; no automatic resumption |
| Guided exact-position resume | Same attempt, no new credit; no extra assistance merely for pausing |
| Recovery requiring replay/uncertain position | Explicitly retain `recovery-replay` assistance; never silently restore a clean attempt |
| Decode/missing/hash failure | Visible technical block; no learner grade; retry captured bytes or discard |
| Replaced current content | Resume verified captured assets, not replacement assets; recovery cannot approve a new draft |

The initial source snapshot is not enough: exercise actual callback races, process death and
interrupted seeks. Immutable submitted results must remain separate from later playback state.

Required pictures must be visible during listening and answering on a narrow phone in both themes,
with clear orientation, landmarks and role markers. No highlighted correct route/option or
answer-bearing title. Text choices can be read with normal accessibility services; spoken choices'
accessible button names expose only their numbers until help/feedback.

Contract 1 provides neutral picture descriptions for **unscored described study**, not a claim of
equivalent scored nonvisual assessment. A learner selecting nonvisual presentation is explicitly
offered described study or another eligible task for essential-visual items; no silent conversion,
failure grade or full-assessment claim. Do not infer blindness merely from TalkBack being enabled.
Missing required graphics block both visual scoring and assessment. A future scored accessible
equivalent requires its own reviewed evidence/policy, not an alt-text shortcut.

## 5. Diagnostic Japanese exam-style session

Assessment is **diagnostic only**: no SRS writes, no introductions and no daily learning-budget
reservation/debit. Entry explicitly warns that listening creates familiarity and a 24-hour cooldown
for exposed stories. It acquires the same source's active-activity guard; a legacy/guided attempt
cannot run concurrently. Preflight/failed start does not acquire lasting ownership or mark exposure.

Default pool selection requires unseen exposure identities. An explicit **Include familiar material**
option allows cooldown-eligible retakes, with familiar counts disclosed before commitment. Never
silently relax it, repeat a story, count an example as a scored item, or use a different task family
to fill a shortage. All selected packages/media/visual-accessibility conditions are checked and
captured atomically before playback. A stale preview requires a new confirmation, not stale consent.

### Bounded blueprint `n5-section-v1`

| Group, in order | Scored items | Approved pool minimum | Separate worked example |
| --- | --- | --- | --- |
| Task-based | 7 | 8 | 1 |
| Key-point | 6 | 7 | 1 |
| Verbal expression | 5 | 6 | 1 |
| Quick response | 6 | 7 | 1 |
| Total | 24 | 28 | 4 |

E's four scored pilots count toward the 28, not as the four worked examples. The extra item per
type allows a learner who used all four pilots to still have a first-encounter 24-item section.
The pool supplies only one bounded form plus limited alternatives, not endless unseen assessments.
All 32 stories are independent. E obtains fresh bounded authorization after the four-pilot gate.

Each group begins with its approved Japanese instruction and worked example. An example has the
same evidence/choices, a 10-second answer window and 10-second visible answer/explanation, but no
grade/denominator. Its separate exposure is retained. The following scored items omit the already
completed generic instruction, not their spoken focus/evidence.

Contract-1 timing is an **app design**, not an asserted official regulation: show visual/text
evidence for 5 seconds before its item audio (no such window for quick response); play all required
segments once; then allow 15 seconds for task-based/key-point answers and 10 for spoken-choice
answers. No arbitrary silent padding of recordings. The example uses the same visual preview rule.
An answer can change within its window; explicit Skip locks an unanswered response. Neither
selection nor Skip advances the clock early. Deadline commits the current choice/skip and advances.
No back/edit of finalized items and no scored feedback until completion or explicit ending.

Projected full-section duration, including examples, previews, all stage occurrences and answer/
example-feedback windows, must be **26-34 minutes**. Select by a captured seed/order without replacement
inside each group. E checks the sum of the shortest and longest permitted selections so random
selection cannot accidentally create an out-of-envelope full section. For forks, F additionally
checks the exact selected form; a failing preview is unavailable, not relabeled as full.
If a full form is unavailable, show per-type shortage/cooldown/accessibility/timing reasons and link
to ordinary guided practice. Contract 1 does **not** silently assemble a shortened exam.

Record deadlines against a monotonic clock during uninterrupted execution. All pause, background,
focus loss, unplug/call, process death, manual replay/seek or decoder recovery makes the section
irreversibly **interrupted**. No automatic audio restart. Offer explicit partial ending or
**Continue as interrupted practice**; retain package/order/answers/exposure, pause remaining windows
and permit guided recovery/help, but never regain the uninterrupted assessment label. Decoder
failure before sufficient evidence is `not-assessed`, not wrong. Process restoration cannot trust
an old monotonic deadline or resume a clean timed label.

Persist each finalized diagnostic response and cursor atomically; final closure is idempotent and
cannot repeat a response or any SRS write. Cancellation keeps finalized items; the unfinished current
item and unvisited items are `not-assessed`, unless an explicit skip/deadline already finalized them.
No exposure is invented for unvisited items. Settings, midnight and source changes do not reselect,
re-budget or reinterpret a captured form.

Report `correct / assessed`, where assessed includes correct, incorrect and deliberate/deadline
unanswered items; separately report unanswered, not-assessed, planned total and example count.
Show task type, first/familiar exposure, assistance, visual involvement and interruption status.
Only 24 finalized items within uninterrupted conditions count as a completed full-form attempt.
No scaled JLPT score, pass prediction, certification or claim that familiar performance proves transfer.

## 6. Compatibility, persistence and delivery gates

Use separate optional `listeningTasks`, `listeningTaskAudio`, `listeningTaskImages` maps and
`listening-tasks/`, `task-audio/`, `task-images/` folders. Keep manifest schema 1 and all legacy maps.
Old readers ignore these maps/folders; their archive inflation/entry/download limits still apply.
Never put new short audio in legacy `audio`: that parser validates every entry, even unreferenced
ones, and an error with cached listening currently rejects the whole sync.

Validate known task schemas/capabilities and only their referenced media. Preserve manifest map
shape across future versions. Unsupported task versions/types are explicit unavailable additions,
not corrupt supported data; their unreferenced future media must not enter the old decoder.
Corrupt supported new-task content keeps the last accepted task snapshot with a visible stale/error
notice, without replacing it with a partial successful catalog. Its accepted revision stays old
and the failed import remains retryable at unchanged HEAD. Legacy/unrelated import may continue.
Existing legacy corruption behavior is not relaxed. An unsupported replacement of a known task ID
cannot silently retain that task's *current* availability; historical captured attempts remain intact.

| Client/state | Required result |
| --- | --- |
| Old client, fresh or cached, mixed complete archive | Existing content imports normally; all new documents/media ignored; total archive remains within old limits |
| B client, unchanged repository HEAD | Independent new-task parser epoch forces import; no renderer means explicitly unavailable, never legacy decoding |
| C client | Focused tasks enabled; spoken-choice tasks remain explicitly unsupported by the renderer |
| D/F client | All four renderers supported; assessment additionally requires a valid approved full pool |
| Unknown future task version/type | Explain update requirement; preserve source history and unaffected supported content |
| Corrupt supported document/asset/approval | Explicit error and last-known-good task catalog; no false success, grading or approval |
| Sync/branch change with open legacy/new/assessment activity | Keep captured source/version/order and every required asset; no history reinterpretation |
| App upgrade with unsupported historical policy | Explain inability to resume; allow explicit discard preserving exposure/history, never regrade it |

B owns a preserving Room migration, shared active/exposure/accounting operations, the new task
catalog and durable task/assessment storage. Keep legacy rows and immutable response JSON untouched.
Export v6 retains the existing seven members and adds `listening_task_encounters.json`,
`listening_assessments.json` and `listening_exposures.json` (empty arrays where appropriate).
Include captured policy/source/version, response identities, actual exposure, assistance, outcome/
denominator and nullable assessment-parent/canonical-review links; exclude all binary assets.
Diagnostic/example rows have no canonical review. Update the analyzer to accept/shape-check the
new members without treating them as Grammar evidence. V1-v5 readers/history remain supported;
missing required v6 members are corruption, not an older-format fallback.

### Media feasibility after content #37

This is the historical contract-1 budget. [N4 B](LISTENING-N4.md#b-package-support-scope-and-capacity)
now supersedes its archive capacities with 48/64-MiB app download/retained caps and
42/56/20-MiB publishing compressed/retained/task-media guards; other bounds remain unchanged.
That reviewed capacity change is not additional N5 or N4 spending/publication authorization.

The pulled content tree measured 28,911,586 retained bytes, 38,987,881 inflated bytes and a
22,665,639-byte local ZIP (5,409 entries). The existing conservative 24-MiB ZIP guard leaves only
about 2.4 MiB, insufficient for the intended pool at the existing 32-kbps mono Opus target.

Adopted bounded adjustment in B: **28 MiB conservative compressed publication cap**, retaining
the hard **30 MiB download / 50 MiB retained / 100 MiB inflated / 8,000-entry** app limits and
40-MiB retained / 80-MiB inflated / 7,000-entry publication headroom. Budget **at most 6 MiB**
for all new unique audio/image bytes across the 28 scored items, four examples and instructions.
Keep the single existing archive distribution; no new host, media branch or secret service.
Measure the complete candidate tree, not just the new directory. The budget checker counts
`curriculum` and all new wanted folders consistently with SafeZip; B corrects the earlier curriculum omission.
If final approved output does not fit, stop/review scope or delivery design; do not delete existing
content, silently lower quality, raise hard limits or disguise a pilot as a full section.

### Assigned verification

| Owner | Required focused evidence |
| --- | --- |
| B | Same package/hash fixtures in Kotlin/Python; old/new mixed-archive readers, unchanged-HEAD refresh, unknown versus corrupt input; every short/changed/missing asset and full pin/prune behavior |
| B | Legacy-preserving migration/export/analyzer; cross-catalog admission/allowance, including familiar-but-unintroduced tasks reserving against other modes after study/assessment; shared-story cooldown; stale callbacks, required-stage completion, double actions and midnight/discard atomicity |
| C | Real common host for both focused objectives and text/essential-visual choices; first frame/stage/answer/feedback, filters, Back/source/rotation/process state and native phone-size accessibility |
| D | Three audible/displayed numbers through every restore/replay path; no transcript leakage; required first option plays versus genuine help; native media/focus recovery with C/legacy intact |
| E | Four independently reviewed original pilots actually listened to in the hosts; then separately authorized 28+4 pool, exact-output approval, all-selection timing bounds, complete archive headroom and real graphic/offline phone checks |
| F | Whole-form eligibility/capture, examples and deterministic windows, all interruption/cancellation classes, exactly-once diagnostic results and honest denominators; full approved offline emulator and physical-device run |

Fixtures/decode/ASR are not final listening approval. E's pool acceptance uses C/D and the blueprint,
not F; F depends on E. No paid synthesis/image generation is authorized by this document.

### Private native author preview

`ListeningTaskAuthoringTest` exercises complete **unapproved** packages in the common C/D host
and native player. It seeds only unreserved standalone study captures in an in-memory database
with isolated preferences. It never invents approval, admits drafts to the ordinary catalog,
grades answers, or writes learner SRS/introductions. Study deliberately reveals the transcripts
and feedback; it is author inspection, not an unseen scored-performance simulation.

Without arguments the test uses four synthetic structural fixtures. For a real pending recording,
pass `authorTask=<task-id>` and supply the exact private renderer output. The loader requires
complete unapproved media, bounded files, matching asset hashes, and captured bodies for every
referenced word article. Missing/bad input is an error, never a synthetic-fixture fallback.

For a side-by-side device installation, build the opt-in **non-debuggable, release-signed**
author variant. It requires the existing local release signing configuration and uses
`io.github.stopcran.kanji.authorpreview`, leaving the learner application's installation/data alone.
The ordinary debug/release builds and their default instrumentation target are unchanged.

```powershell
.\gradlew.bat -PlisteningAuthorPreview :app:assembleAuthorPreview :app:assembleAuthorPreviewAndroidTest
adb -s $serial install -r app\build\outputs\apk\authorPreview\app-authorPreview.apk
adb -s $serial install -r app\build\outputs\apk\androidTest\authorPreview\app-authorPreview-androidTest.apk
```

Set `$serial` to the explicitly selected authorized device, `$run` to the private render directory,
and `$taskId` to the pending package ID. USB debugging/ADB authorization is required. The test APK
is an author tool, not an update to the learner APK. Prepare only the selected package/assets and,
when referenced, copy the exact `words\<word-id>.md` bodies into the private bundle's `words` folder.
Do not push credentials, PCM caches or spending records.

```powershell
$remote = "/sdcard/Android/data/io.github.stopcran.kanji.authorpreview/files/listening-author-preview"
adb -s $serial shell mkdir -p "$remote"
adb -s $serial push "$run\packages" "$remote/"
adb -s $serial push "$run\task-audio" "$remote/"
if (Test-Path "$run\task-images") { adb -s $serial push "$run\task-images" "$remote/" }
if (Test-Path "$run\words") { adb -s $serial push "$run\words" "$remote/" }
adb -s $serial shell am instrument -w -r `
  -e class io.github.stopcran.kanji.ui.ListeningTaskAuthoringTest `
  -e authorTask "$taskId" `
  io.github.stopcran.kanji.authorpreview.test/androidx.test.runner.AndroidJUnitRunner
```

Require `OK (1 test)`, not just a successful shell exit. The test captures light/dark screenshots,
plays every stage in fixed order, compares actual native starts with the exact audio hashes,
and confirms no canonical reviews. Its `E-author-<id>.json` and `E-author-<id>-light/dark.png`
outputs are under the target app's external `files` directory. Copy that evidence to the private
review run. It explicitly does **not** declare actual listening approval.

An emulator run is not physical-phone acceptance. An operator must still listen to every actual
stage, inspect the essential speaker/choice graphics and readable text on the physical phone,
and record an honest exact-output declaration through the content renderer. Automated success,
screenshots, synthetic cues, or merely installing these APKs cannot satisfy those human gates.
