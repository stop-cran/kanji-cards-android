# Reading exercises - shared contract

Status: transport agreed for #138; reader/import, automatic Study-next and durable quizzes
are implemented on main. V23's selected API 35 native integration and preservation gates
passed, including real process restart, captured-document/position restoration and direct
review of all six screenshots at font scale 2.0. This is debug/test APK emulator coverage;
the separately verified signed release remains uninstalled and undistributed. Room 26 is
published in main's source and schema export. Native/parser acceptance
does not establish editorial, rights, physical-device or learner-efficacy approval, or
authorize catalog publication.
Content counterpart: [stop-cran/learning-japanese#64](https://github.com/stop-cran/learning-japanese/issues/64).
Reader/import implementation: #139. Comprehension sessions: #140.

## Transport and publication

Reading is independent of kanji-reading drills and Word picture scenes. The EN outer
manifest remains schema 1; RU remains schema 2 with the existing repository-wide language
binding. New optional manifest maps are `reading` (LF-normalized SHA-256 of
`reading/<id>.json`) and `readingImages` (raw SHA-256 of `reading-images/<id>.webp`).
Unknown namespaces are ignored by older clients. Never add Reading JSON to `files`,
`listening` or `scenes`, or add Reading media to Word scene ownership.

Package schema 1 contains:

```text
schemaVersion: 1
id: stable lower-case hyphenated ASCII identity, matching the filename
jlpt: integer 1..5, a provisional learning target
title: localized title
japaneseTitle: Japanese title
levelRationale: base-language explanation of provisional level
provenance:
  originalTitle
  adaptation: {label, basis: original | traditional-plot | public-domain-text}
  consulted: [{originalTitle, edition, sourceUrl, accessed, role, author?, translator?, editor?}, ...] (optional)
  credits: [base-language credit, ...]
  rights: {status: original-wording | public-domain-source-text | permission-recorded, note}
  distribution: {reviewed: [two-letter jurisdiction code, ...], note}
sections:
  - id, text, translation (optional)
    vocabulary:
      - start, end, text, reading, headword, gloss, word (optional canonical card identity)
questions:
  - id, tier (integer 1..4), prompt
    options: [{id, text}, ...]
    correctOptionId, explanation
    evidence: [{sectionId, start, end}, ...]
    image: {path, sha256, description} (optional)
```

All IDs are stable and unique within their owning list. Text is plain Japanese, not
HTML/Markdown. Offsets are half-open Unicode code points in the exact section text.
Reviewed vocabulary occurrences are sorted and nonoverlapping; their `text` must match.
Combining sequences cannot be split. Contextual readings and glosses describe the actual
inflected occurrence, not merely a dictionary substring. A canonical word reference is
optional; missing articles never remove the local gloss.
Unknown fields and explicit null are rejected; omit optional fields. `word` is a canonical
Japanese card ID, not an ASCII package ID. `consulted` must be nonempty for non-original
bases; its roles are `plot-check` or `wording-source`, accessed dates are real ISO dates,
and source URLs are HTTPS. `original-wording` cannot claim a consulted wording source.
An empty `distribution.reviewed` means no jurisdictions reviewed, never worldwide clearance.

Titles, contextual glosses, question instructions/options/explanations and picture
descriptions follow the source's base language; Japanese text/readings and stable IDs
do not change for UI localization. No runtime tokenizer, dictionary service or network
lookup is required. Every annotated occurrence is tappable regardless of JLPT or saved
familiarity. Displayed reading support must not imply that a level tag proves knowledge.

The bounded parser accepts up to 100 sections, 100,000 UTF-16 units overall, 10,000 per
section, 2,000 reviewed occurrences per section and 128 questions. These are hostile-input
limits, not the pilot's length target. Each question has 2-6 distinct fixed choices,
exactly one declared correct ID and at least one valid evidence span. Pictures are
text-owned WebP assets, at most 150 KiB, manifest/hash-bound and decoded before admission.
Required invalid media makes its package unavailable, not a wrong learner answer.

## Source and revision ownership

One accepted package catalog per source is replaced atomically with ordinary content.
The source-scoped Reading parser epoch advances only with accepted replacement, including
an empty optional catalog; upgrades refresh unchanged remote revisions when necessary.
Invalid packages are reported separately from valid retained packages. App import,
archive limits, Room migration and sync diagnostics are #139 work, not assumed UI reuse.

A durable reader/attempt captures source, branch, revision, full package, referenced word
bodies and any question pictures. Sync or source switching cannot retarget it. Position,
reading completion, assistance policy and question/option order belong to the capture.
Nested captured articles retain the same source and restore the reader position. Ordinary
browsing still passes current language/source admission; a durable capture is not a
generic language-mismatch exemption for fresh starts.

The Room 24 checkpoint (retained by Room 26 on main) stores captured word bodies in separate source/text/capture/word rows, not one
aggregate reader field. The 23→24 migration reads old vocabulary JSON in bounded slices
before splitting it, preserving captured wording, order, positions, media and history.
The retained `wordsJson` column is cleared to `[]`; progress exports reconstruct the
same `wordBodies` array from the capture-owned rows. This avoids a vocabulary aggregate
exceeding Android's CursorWindow on reopen; it does not raise content/package limits.

## Learner policy and remaining acceptance

- N5 authoring target, selected by the user: about 1,000-1,500 Unicode code points across
  plain section texts, retaining eight sections. This supersedes the earlier authoring
  band; it is not a parser minimum or JLPT certification. Reading-load and readability
  evidence remain pilot-review work.
- Furigana: hidden for new captures so tap-for-reading/meaning is the primary aid. The
  show/hide control persists per capture; existing saved choices are retained. When shown,
  ruby shares the ordinary text baseline and covers only kanji runs, not kana prefixes,
  internal kana or okurigana. Whole-word reviewed readings are split using matching kana
  anchors; compounds keep a grouped reading (not a guessed per-kanji split). Ambiguous or
  mismatching anchors leave the occurrence plain; its full reading remains in lookup.
- Reading during questions: the user chooses open-book or closed-book per quiz. Capture
  that choice in results. Both modes share one per-text unlock score, as chosen by the user.
  Question-owned images remain visible in both modes; evidence spans appear in feedback.
- Accepted unlock policy: a completed quiz with at least 75% correct at the current
  highest tier unlocks the next tier for subsequent quizzes. The quiz must actually
  include that tier. Both book modes contribute to the same per-text progress; no
  two-day requirement or one-credit-per-day gate applies. Harder questions are never
  added halfway through the attempt that unlocked them.
- Scheduling: the user selected separate open-book and closed-book schedules, independent
  of shared tier unlocks. Record one FSRS review per completed passage quiz, not one
  event per correlated question. A passing highest-tier score records Good; a completed
  non-passing quiz records Again. Incomplete/discarded attempts record no review.
  This avoids treating correlated questions about one reading as independent exposures.
- Explanations and evidence appear only after the whole quiz is completed and submitted,
  so early feedback cannot spoil later questions. Answers and option order are durable;
  submission is idempotent and its response remains immutable.
- Daily-new accounting: a newly introduced source/text uses one shared repository slot
  across both modes, durably reserved at the first quiz start. Submission converts that
  reservation into the first recorded introduction; discarding an unfinished quiz releases
  it without inventing a review. Retaking or switching modes does not debit another slot.
  Ordinary reading, lookups and local word selection do not themselves introduce the text.
- Highest-tier sampling: four questions when available, plus at most one from each
  lower tier. Show the actual required correct count and an insufficient-pool notice
  when fewer than four highest-tier questions are available: 75% then means all correct.
  The expanded literary pilot needs a grounded pool, not weak items added to fill a quota.
- Study next: persistent source/word intent, deduplicated and honored in the next eligible
  Word quiz's actual deck and direction without an upfront chooser. It does not bypass
  new-item allowance, stack membership, content capability or directional gates.
  Deferred intents remain visible; consumption occurs only when that selected word's
  answer is recorded, qualified by its captured selection token. An unavailable picture
  releases only its reserved new slot so another eligible selection can be attempted.

## Editorial and acceptance gates

Start with an original, clearly labeled N5 adaptation of Momotaro. Authored cognitive
operation and evidence scope are separate: local paraphrase/reference questions may stay
within one section; motives, causes and whole-text questions use distributed evidence
when their reasoning requires it. Distractors must be wrong in this adaptation, not merely
different from remembered versions of the folktale.

Record the precise source edition, original publication/contributors, adaptation history,
credits and applicable distribution rights. Availability on Aozora or an author's death
date is not blanket clearance. The parser verifies provenance presence, not legal status.

The content pilot needs blind facet-split correctness, consistency/quiz-safety and
comprehensibility reviews by separately verified model families. Report actual length,
vocabulary/grammar exceptions and readability evidence. Neither an author table nor a
validator certifies an official JLPT level.

A tiny original fixture unblocks parser/UI work but is not literary-pilot acceptance.
Require the exported real pilot, old-reader compatibility, import/sync and migration
regressions, source/linked-document restoration, selected-word gating/budget cases,
first/later tier sessions, immutable responses, changed package/media failures, native
recreation/relaunch and enlarged-text checks before adoption. Physical-device and
learner efficacy claims require their own actual evidence.
