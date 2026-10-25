# N4 listening extension

Adopted design from [content A / #48](https://github.com/stop-cran/learning-japanese/issues/48)
and its independently reconciled A-G work items. **B implements package/scoped-practice/capacity
support; C's diagnostic profile and the real media pool remain pending.**
The [N4 authoring profile](https://github.com/stop-cran/learning-japanese/blob/main/docs/listening-n4.md)
and [source ledger](https://github.com/stop-cran/learning-japanese/blob/main/docs/listening-n4-sources.json)
own language/coverage evidence. This extension preserves [contract 1](LISTENING-TASKS.md)
for N5 and changes only the explicitly identified N4/runtime-capacity behavior below.

## B: package support, scope and capacity

[B / #75](https://github.com/stop-cran/kanji-cards-android/issues/75) supports existing
schema-1/policy-1/N5 Japanese tasks and new schema-2/policy-1/N4 tasks. The latter use the
same four stage/choice shapes and separate task/media namespaces. Legacy English-question
packages remain N5-only. Strict unsupported-version detection precedes media decoding.
Accepted source-scoped task parser epoch **2** advances support, not historical learning policy;
failed refresh retains the accepted catalog, captured encounters, identities and media pins.

Source-saved practice scope is N5 by default, with an explicit N4 + N5 choice. Compute
shared reservations, allowances and actionable due backlog source-wide; apply scope/format
candidate eligibility before bounded fresh selection and revalidate admission against it.
Hidden candidates must not consume visible fresh slots. Keep source-wide backlog effects
visible even when corresponding due items are outside the scope. Level scope governs
browsing, Study, new/due/extra queues, home summaries and reminders; format is a browser filter.
Captured activities outside a new scope remain explicitly resumable. No new stack, SRS
identity, allowance, auto-enrollment or mastery gate is introduced.
Unsupported placeholders have no trusted level and remain visible under All formats as
unavailable notices, never eligible candidates. Level metadata is read from accepted task JSON.
The selected level persists per source, while the format is saved browser state.

App download/retained caps are 48/64 MiB; content publication guards are 42 MiB
compressed, 56 MiB retained and 20 MiB combined task audio/images. Inflation remains
100 MiB app/80 MiB publishing, entries 8,000/7,000, audio files 512 KiB and images 150 KiB.
Path, codec, hash, exact-approval and expansion checks remain strict. The last measured
baseline and 9-13-MiB new-media projection are in the source ledger; output measurements,
not the projection, authorize fit.

An archive above the older 30-MiB download cap cannot be fetched by those apps, even though
they skip schema 2. They retain their last-known-good offline collection and report update
failure; continuing sync requires the upgraded release. Deliver the new APK before such
runtime publication, without claiming a copied APK was installed.

## C: captured N4 diagnostic profile

[C / #76](https://github.com/stop-cran/kanji-cards-android/issues/76) adds exact-level
`n4-section-v1`, separate from the cumulative practice selector. Preserve N5's existing
profile, example eligibility, timing, captures, results and exports.

| N4 family | Scored selection | Minimum scored pool | Worked examples |
| --- | --- | --- | --- |
| Task-based | 8 | 16 | 1 |
| Key-point | 7 | 14 | 1 |
| Verbal expression | 5 | 10 | 1 |
| Quick response | 8 | 16 | 1 |

The projected N4 full-form envelope is initially 31-39 minutes, using existing 5-second
previews except quick response, 15/10-second focused/spoken-choice answer windows and
10-second example answer plus 10-second explanation windows. Count every recorded stage
occurrence; do not add fictional instruction completion or silent padding. Counts and
tolerances are project design informed by the 2018 sample, not universal JLPT specifications.

Default N4 scored stories must be unseen. Worked examples may recur after the unchanged
24-hour exposure cooldown, with example familiarity separately disclosed. This is N4-only:
retain N5's existing familiarity behavior. Familiar-scored opt-in remains explicit.
Two nonoverlapping scored forms are a pool capacity before scored exposure, not a guarantee
after practice or of two immediately available sections.

Capture selected profile/level and all source, package, media, policy, timing and consent
data. Stale previews require fresh confirmation; live settings cannot retarget a capture.
No cross-level fill, repeated stories, example-as-score, implicit shortened section or
hidden relaxation of media/accessibility/cooldown rules. Preserve the common active-activity
guard, all pins, interruption labeling and no-SRS/no-introduction diagnostic behavior.
Report raw captured-profile denominators and familiarity, never an official scaled score.

The normal pool checker requires approved runtime packages. An explicit read-only
unpublished-candidate path may verify complete real media, identities, counts and all-form
extrema without granting approval or scoring readiness. Use the shared timing machinery;
do not duplicate the player, grading or content-integrity implementation.

## Sequential acceptance and publication

A -> B -> C -> [D pilots](https://github.com/stop-cran/learning-japanese/issues/49) ->
[E focused pool](https://github.com/stop-cran/learning-japanese/issues/50) ->
[F spoken-choice pool](https://github.com/stop-cran/learning-japanese/issues/51) ->
[G integration](https://github.com/stop-cran/kanji-cards-android/issues/77).

D's eight scored pilots are included in 56. E adds 26 scored plus two examples; F adds
22 scored plus two examples, producing 60 packages. D-F own per-package independent review,
full-order actual listener/picture acceptance and guided-native evidence; they publish
authoring/source/tooling changes and retain frozen private media candidates.

G owns integrated real-media/full-section and physical-phone checks on the unpublished
candidate through explicitly unscored author/test paths. Only after these gates pass, or
a fresh bounded N4 owner-testing exception is granted, does G bind accurate runtime
authorization and publish actual packages/media/manifest after normal-import verification.
Obtain exceptions before the affected push, never retrospectively.

Fresh bounded pilot and later batch spending approvals are separate from software/plan
approval. N5 allocations and trial waivers do not carry over. Fixtures, successful commands,
ASR, file transfer and general owner enthusiasm do not supply unperformed listening/device
acceptance. Older N5 acceptance issues remain separate.
