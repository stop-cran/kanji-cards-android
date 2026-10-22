# Content schema

The authoritative content format lives with the content: [docs/content-format.md](https://github.com/stop-cran/learning-japanese/blob/main/docs/content-format.md)
in the default repo `stop-cran/learning-japanese`.

What the app enforces when importing (`core/content/ContentParser.kt`, `FrontMatter.kt`, `SafeZip.kt`):

- The repo archive (`codeload.github.com/<owner>/<repo>/zip/refs/heads/<branch>`) must contain `manifest.json` with `schemaVersion` 1.
- Only `manifest.json` and files directly in `kanji/`, `words/`, `articles/`, `strokes/`, `counters/`, `grammar/`, `scenes/`, `listening/` are read,
  plus `images/*.webp` (at most 150 KiB each) and `audio/*.ogg` (at most 512 KiB each).
  Zip-slip paths, more than 8,000 entries, wanted text files over 2 MiB or wanted data over 50 MiB are rejected.
  The separate 100 MiB inflated limit includes skipped entries too.
- Images are listed in the manifest's separate `images` map with SHA-256 over the raw bytes (no LF normalisation). A word's `image` and
  `image_scene` keys are read together; an image that is missing, oversize, not WebP, hash-mismatched or shared by two words is reported as a
  problem and only that word's picture is dropped.
- Optional `scenes/<word>.json` catalogs use a separate manifest `scenes` map with LF-normalized hashes, included in `contentVersion`.
  They must not appear under `files`, because older clients discard this directory. A catalog has integer `schemaVersion: 1`,
  a filename-matching `word` and a nonempty `scenes` array. Each entry requires unique word-scoped lowercase kebab-case `id`,
  nonblank English `sense`/`description`, a unique flat WebP `image` path and an explicit JSON boolean `quizEligible`.
  Article-only variants remain imported but are not quiz candidates. Asset ownership stays word-scoped, including legacy references.
- A declared catalog is authoritative. Missing, hash-mismatched or invalid catalogs disable that word's picture eligibility without
  deleting its lexical card. Only absence permits a synthetic eligible legacy scene. Retained legacy fields must match an eligible
  catalog scene and its description exactly; otherwise the catalog is rejected. A failed individual asset drops only its variants.
- The Android sync boundary checks actual WebP decoding, not just its envelope, and rejects sides outside 1-2048 pixels. Display
  decoding is downsampled to at most 512 pixels per side. Picture questions require all of their images to be ready and at least
  two permitted choices; unavailable media never counts as a wrong answer. Article-only scenes do not enter either picture direction.
- Every file listed in the manifest must match its SHA-256 (computed over LF-normalised bytes); mismatching, missing or malformed files are
  skipped and counted as problems instead of failing the sync. Duplicate kanji titles are rejected (titles are quiz answers).
- Front matter is a restricted subset: `key: value` scalars and inline `[a, b]` lists (commas inside quotes are kept; an unterminated quote
  makes the value a scalar). A scalar in a list field (`tags`, `onyomi`, `kunyomi`, `distractors`, word `kanji`/`tags`) rejects the file.
  The closing `---` must be a standalone line; `---\n---` is a valid empty header.
- All word front-matter keys, including unknown fields, must be unquoted, unindented ASCII identifiers matching
  `[A-Za-z_][A-Za-z0-9_-]*`; whitespace before `:` is allowed. All raw keys are validated before duplicate-key collapse.
  Quoted/escaped, tagged, anchored/alias and complex keys are rejected with a path-specific import problem. Whole-line
  comments and unrelated quoted value text do not create key declarations.
- A word header closes with a standalone `---` marker, allowing trailing whitespace or a whitespace-separated `#` comment
  (for example, `--- # comment`). Prefixes such as `---notes`, `----` and `---#comment` are rejected, not treated as closing
  delimiters that could hide later keys. These word-only restrictions leave legacy kanji and article parsing unchanged.
- Word front matter may specify `jlpt: 1` through `jlpt: 5`, an explicit community vocabulary label independent of kanji difficulty.
  This takes precedence even for kana-only words or words with harder, unlevelled or missing kanji. Without it, the app retains the legacy
  hardest-kanji inference; unlabelled words with no kanji or any unknown kanji level appear only in "All words".
- Omit word `jlpt` when no sourced level is available; only omission enables the legacy fallback. When present, write one top-level
  unquoted scalar `jlpt: 1` through `jlpt: 5`. Blank/null values, quoted strings, lists (including `[]`), invalid or out-of-range values,
  duplicate declarations and indented/multiline declarations are rejected with a path-specific import problem. Raw declarations are checked
  before unquoting or duplicate-key shadowing can hide an invalid value. Existing kanji `jlpt` parsing is unchanged.
- Word tags remain free-form and are preserved as authored. No `jlpt-nX` word tags are required or generated, and word level metadata does
  not change quiz tag scoring.
- Optional word `quiz_exclusions: [other-word]` lists exact written-word IDs, not readings or titles. Omission and `[]` mean no curated
  exclusions. A declaration on either word keeps that pair apart, both against the target and among distractors, in both quiz directions.
  Exclusions are not transitive and do not infer synonyms. Exact title/form deduplication and scoring among eligible candidates are unchanged;
  an insufficient pool returns fewer options, possibly only the target, rather than reintroducing an excluded word.
- Declare `quiz_exclusions` once with an unquoted, unindented key and an inline string list. Plain string IDs and simple single/double-quoted
  IDs are supported; numeric/boolean/null/date scalars must not masquerade as strings. Empty IDs, duplicate/self IDs, nested lists/maps,
  aliases/tags, escape sequences, embedded commas/matching quotes, duplicate declarations and multiline shadowing are rejected. This is not full YAML.
  References must resolve to successfully imported words in the same snapshot; invalid referencing files are skipped with path-specific
  problems, including references made dangling when another invalid word is skipped. Kanji front-matter behavior is unchanged.
- Word stacks stay cumulative: N5 contains level 5, N4 contains levels 4 and 5, and "All words" includes unlevelled words too. The same
  selection drives quiz sessions, home counts, reminders and N5 advancement. Existing unlock and per-direction scheduling rules still apply.
- Review state is keyed by (content source `owner/repo` lowercased, stack, item, mode, reading), so content edits never reset scheduling;
  pointing the app at a different repository or studying a different stack uses a separate set of review states. Changing a word's level
  changes stack membership, not its identity; state in its previous stack is retained, not copied to other stacks.

The historical Room database version 3 added nullable `words.jlpt` and non-null `words.quizExclusions` (default empty string, using the existing
list separator). The version 2-to-3 migration keeps all content, word IDs, review states and review logs; existing rows initially have no explicit
level or exclusions. It clears only sync metadata so normal home-screen sync reimports even an unchanged content revision and fills in metadata
that older app versions ignored. Cached content remains usable with legacy behavior while offline. Version 1 databases upgrade through both
registered migrations. The content manifest remains schema version 1; regenerate hashes and `contentVersion` when changing word metadata.
`contentVersion` is the update contract: a new commit with an unchanged `contentVersion` is treated as up to date and not imported.

Room v10 adds `word_scenes` and nullable `words.quizImage`. The v9 migration retains every existing schedule and Grammar record and
synthesizes named legacy metadata for stored word images. Scene parser epoch 1 is source-scoped and independent of Grammar epochs;
until refreshed it bypasses revision/version/launch-throttle skips. It is recorded only after successful snapshot replacement.
The existing Picture mode remains `WordPicture` (`pc`); the inverse is `WordToPicture` (`wp`). Both schedule words, not scene IDs.

## Grammar content JSON v1

Each manifest-declared `grammar/<id>.json` is one set with `schemaVersion: 1`, a matching lower-case `[a-z0-9-]+` `id`, a nonblank `title`,
and an `article` path to an imported `articles/<slug>.md`. The set contains:

| Field | Contract |
| --- | --- |
| `constructions` | Unique valid `id` and nonblank `label` for each answer class. A class may contain several contextually fitting forms. |
| `relationships` | Unique valid `id`, nonblank `label`, and at least two distinct known construction IDs in `constructions`. |
| `exercises` | Nonempty list with unique valid exercise IDs. Every member of each relationship must be targeted by an exercise in that relationship. |
| `introductionGroups` | Optional authored groups, default `[]`, with source-global IDs and local facet/context membership; explicit null is invalid. |

An exercise has `id`, `target`, `relationship`, `prompt`, `translation`, `evidence`, `explanation`, `answer`, `acceptedAnswers` and `options`.
The target must belong to the named relationship; `answer` must equal the target ID. The prompt contains exactly one `____` blank.
Translation, evidence and explanation are required, nonblank strings. Options have unique relationship construction IDs, nonblank `label`
and `rationale`, at least two choices, and exactly one occurrence of the answer ID. The nonempty `acceptedAnswers` list contains no blank
forms and must include the correct option label after trimming.

Optional `additionalCorrectOptions` is an array (default empty; explicit null is invalid) of `{id, label, rationale}` objects.
Each field is a nonblank string; IDs are lowercase slugs local to the exercise, distinct from every base/additional option ID.
They identify authored realizations, not constructions or new schedules. Every additional label must match an accepted typed answer
after trim then NFKC normalization. All base/additional labels must remain distinct after that normalization; no base distractor
may match any accepted answer. Malformed declared grading metadata rejects the set, unlike optional vocabulary hints.
Old clients ignore this additive field and keep the safe one-correct base `options`.

Grammar choice presentations independently sample one in five eligible appearances: append one randomly selected authored alternative,
retain the primary and all base distractors, then shuffle. Other appearances show only base options; typing is unchanged.
One tap on either displayed correct option completes the question. The question captures order, labels, rationales and correct IDs;
callbacks are bound to that presentation instance. Feedback highlights both displayed correct answers after mistakes or give-up and
separately marks the chosen wrong answer. Typed feedback can explain all authored alternatives without limiting typed acceptance.

An exercise may also contain one optional `jlpt` integer in 1..5. Omission means Unclassified; explicit `null`, strings, booleans,
fractions and out-of-range values reject the set. Do not duplicate the level as tags or add cumulative memberships. The level describes
the required grammar/contrast, not incidental vocabulary or every accepted alternative. New-material scope is cumulative (`jlpt >= selected
level`); browsing is exact. Neither creates another exercise identity or schedule.

Group definitions require `id`, `label`, `facets` and `members`; optional `next` and `requires` default to empty arrays.
Each facet needs two distinct example keys and one local target/answer class. One exercise has at most one authored owner.
Recommendations are `{groupId, importance, reason}` with integer importance 1-3; prerequisites are group-ID strings.
Unknown nested fields, null arrays, duplicate groups/ownership and missing/cyclic prerequisites reject declaring files and dependents;
missing optional recommendations only warn and drop the edge. See [the complete introduction contract](GRAMMAR-INTRODUCTIONS.md#additive-metadata).
Accepted snapshots with rejecting Grammar diagnostics block guided new intake, never reinterpret malformed groups as legacy contrasts.
The focused pilot ranks eligible next groups by maximum incoming importance from supported familiar groups; links are not prerequisites
or automatic familiarity. Phase D uses the already-retained epoch-2 fields without another parser epoch or Room migration.

The importer also rejects duplicate set/exercise identities across the content snapshot and missing article references. Construction and
relationship IDs are validated within a set, not as global grammatical concepts. Structural validation does not establish linguistic
uniqueness: authors/reviewers must keep every fitting in-pool completion, never use an accepted equivalent as a wrong button, and make each
accepted string fit the actual blank and the app's 80-character typing limit. There is no arbitrary count cap on accepted alternatives.
See the content repository's authoring guide for the source/review requirements.

Typing grades exact membership after trimming and Unicode NFKC normalization of both the input and accepted forms. There is no kana
folding, romaji conversion, typo tolerance or substring matching. Choice grades the option ID, not its label. The complete Japanese prompt
and English meaning appear before answering; feedback and the linked article do not supply hidden required context.

### Optional occurrence-addressed vocabulary

An exercise's optional `vocabulary` object has exactly `schemaVersion: 1`, `promptSha256` and `spans`.
The hash is SHA-256 of the exact UTF-8 prompt, without normalization. A span has integer `start`/`end`, exact `text`,
displayed-form `reading`, dictionary `headword`, context-specific English `gloss` and optional canonical `word` ID.
Offsets are half-open Unicode-code-point intervals in the original prompt. Rendering converts them to UTF-16 and shifts only
ranges after the filled blank; it never searches for guessed substrings or annotates the inserted answer.

Spans must be sorted/nonoverlapping, match their occurrences, avoid the blank and not split combining sequences.
Limits: 128 spans; 80 code points for nonblank form/reading/headword/word IDs; 240 for the nonblank gloss.
Word references are flat IDs without slashes, control characters, `.` or `..`, resolving to accepted `words/<id>.md` entries.
Local glosses without word references are valid. Repeated spellings may have distinct readings/glosses and share a word identity.
Omission remains valid legacy content; explicit null, malformed fields/types or a stale hash disable only this exercise's hints.
Missing article references are removed while preserving local glosses. These diagnostics are reported separately from rejecting
Grammar errors and never populate `sync_meta.grammarProblem` or block otherwise valid guided introductions.

`vocabularyProblem` is an internal import/cache diagnostic, not an authored field; supplied values are stripped before import.
Typed Room serialization retains hints and real diagnostics. Epoch **3** introduced vocabulary support; the current Grammar parser
epoch is **4**, also recovering additional correct choices discarded by older typed caches even at the same commit/manifest.
Vocabulary support itself needs no additional Room migration.
The maintained content repository has a revision-bound authoring/coverage gate; the app does not require its inventory or mandatory
annotations from legacy/fork content. See that repository's `docs/content-format.md` for editorial exclusions and provenance.

## Room content replacement

The current Room version is **12**. The 7-to-8 migration creates `grammar_sets` (key: source + set ID) and `grammar_responses`
(key: `reviewLogId`) and clears only sync metadata to force a content reimport. Existing content, review states and history are preserved;
all older versions use the registered migration chain, with no destructive fallback.
The additive 8-to-9 migration preserves that snapshot and all history, creates source-keyed `grammar_focus` (`revision`, cursor JSON),
and adds nullable `sync_meta.grammarProblem`. Atomic replacement invalidates outstanding focus tickets and stores snapshot diagnostics.
The 11-to-12 migration adds nullable `grammar_responses.choiceSnapshot` JSON without reconstructing old presentations or resetting history.

Grammar uses `(sourceId, stack="grammar", item=exerciseId, mode="Grammar", reading="")` as its review-state identity. The generic Room
`kanji` column holds the exercise ID. Choose and type are presentations, not separate modes or schedules. Editing a set's prose without
changing exercise IDs preserves progress; switching repositories uses another source scope. Branches of one repository share that scope.
Content replacement never clears review states, canonical logs or Grammar response details.

Grammar levels need no Room migration: `grammar_sets.json` retains the optional field, omitting absent/default-null values on serialization.
Source-scoped settings store independent introduction/browse scopes (both default All) and the last successfully imported Grammar parser epoch.
Epoch 1 supports retained levels; epoch 2 additionally retains and validates groups. A pending epoch refreshes typed caches even when the
head SHA/contentVersion is unchanged or the launch check is recent.
Only successful atomic replacement advances the epoch; it does not bypass rejected-file/shrink protection or offline archive backoff.
Epoch 0 permits only unguided All new intake; epoch 1 permits level-aware unguided intake; guided introductions require epoch 2.
Started reviews remain safe throughout, and the selected scope/pilot is never silently changed. Failed downloads retain the previous snapshot
and diagnostics, rather than marking last-known-good content invalid.

`DatabaseMigrationTest` checks upgrades from versions 1, 2, 3, 7, 8, 9 and 11, native-SQLite reopen preservation and the Grammar event/export join.
`ContentSyncTest` checks a changed Grammar snapshot without losing its existing progress or response history.
`GrammarFocusPersistenceTest` covers reservation/reopen, stale tickets, rollback and source isolation. Cursor updates share the answer's
state/log/detail transaction. The cursor retains automatic ordinal, manual selection nonce, active group/origin/seed and recorded exposure
IDs with their facet/context keys. It is omitted from progress ZIPs; Android backup includes the database and preferences normally.

## Listening JSON v1

See the authoritative [N5 schema and authoring contract](https://github.com/stop-cran/learning-japanese/blob/main/docs/listening-n5.md).
Listening documents and binary audio use separate manifest `listening` and `audio` maps; legacy `files` cannot contain them.
JSON hashes are LF-normalized; audio hashes are raw bytes. Both maps affect `contentVersion`. Listening parser epoch 1 forces
reimport of formerly ignored content even at an unchanged commit/version. Room v13 preserves existing progress while adding
passages, hash-keyed audio and durable encounters.

Only schema 1 and integer JLPT 5 are supported. Stable filename-matched IDs identify one or two speakers, ordered turns,
exactly three four-option questions with one main point, vocabulary tips and optional hash-bound contextual WebP.
Generated mono Ogg Opus is limited to 15-60 seconds and 512 KiB. Container framing, sequence, CRC, duration and hashes are
checked; actual playback still must succeed. Audio metadata includes ordered exact turn boundaries.

`contentSha256` is canonical UTF-8 JSON over the whole package except `audio` and `audioReview`: sorted object keys,
preserved arrays, unescaped Unicode, integer numbers and no optional whitespace. The audio and declared final listening
approval both bind that hash and the recording hash. Changed questions, pronunciation or picture bytes invalidate approval.
Absent audio/review is an explicit pending draft, not grading-ready content. A bad listening import preserves an existing
listening snapshot even with force; valid pending drafts may replace old content. Active encounters pin their original audio
and presentation independently of later content changes.

## Progress export v5

**Settings → Export progress** writes seven UTF-8 JSON members. Database rows are read in one transaction:

| ZIP member | Meaning |
| --- | --- |
| `export_manifest.json` | `{"formatVersion":5}`; independent of content JSON/manifest schema versions. |
| `review_state.json` | Current SRS rows: `sourceId`, `stack`, `kanji`, `mode`, `reading`, `phase`, `stability`, `difficulty`, `dueMs`, `lastReviewMs`, `reps`, `lapses`. |
| `review_log.json` | Canonical recorded events: `id`, `sourceId`, `stack`, `kanji`, `mode`, `reading`, `grade`, `atMs` (Unix milliseconds), nullable `sessionId`. Grades: Again=1, Hard=2, Good=3, Easy=4. |
| `grammar_responses.json` | `reviewLogId`, `sourceId`, `stack`, `exerciseId`, `constructionId`, `relationshipId`, `promptType` (`choose`/`type`), `response`, `correct`, nullable structured `choiceSnapshot`. |
| `study_sessions.json` | Session `id`, `sourceId`, selected `stack`, `mode`, `extra`, `startedAtMs`, nullable `endedAtMs`, `endReason`, `answered`, `correct`. |
| `listening_encounters.json` | Source/passage identity, captured JSON/hash/audio hash, current playback state, immutable submitted `response`, exposure/first-encounter timestamps, reservation/introduction flags, canonical review link and closure. No image/audio bytes. |
| `settings.json` | Stored preferences, including source configuration and cached sync metadata. |

For a recorded Grammar answer, state, canonical log and response detail are written in one transaction. Join `reviewLogId` to the unique
log `id` for grade/time; source, stack and exercise identity must also match, with canonical mode `Grammar`. Choice `response` is an option
ID, typing `response` is normalized text, and give-up records an empty string. Details record the exercise's target construction and
relationship, not the wrong choice's construction. There is no separate guessed flag: the current UI's correct-choice "I guessed" yields
Hard. Successful practice confirmations create no state update, log or detail, even though the session's displayed tally includes them.
The export's answer count is the number of canonical log rows, not log rows plus details.

New choice records contain `choiceSnapshot: {"schemaVersion":1,"interaction":"choose-any","options":[...],"correctOptionIds":[...]}`.
Options retain their actual displayed order and `{id,label,rationale}`; the correct-ID set contains one or two displayed IDs.
The selected response is interpreted against this snapshot, never current content. Typed and pre-v12 records have null snapshots.
There is no second answer event for the unselected correct option and no retroactive reconstruction of historical choices.

Join a non-null `review_log.sessionId` to `study_sessions.id`. Each prepared study queue gets a UUID; repeated answers keep it,
and a new start or "Practice more" gets another. `extra=false` means the ordinary due/new queue, not necessarily due-only cards.
`mode` matches the log except `KanjiReadings`, which groups both `KanjiOn` and `KanjiKun`. A borrowed padding card retains its owning
log `stack`, which may differ from the session's selected stack. Opening an article, rotating or backgrounding does not split a session.
Exhausting the queue records `endReason="completed"`; removing its route early records `"left"`. The end marker follows all pending
recorded answers. Ended sessions' `answered`/`correct` match the UI tally, including successful practice confirmations, but excluding
study views, traces and skipped questions. These are answered-attempt counts, not every on-screen presentation or per-card attempt logs.
If no ending was observed (still open or process killed), all four ending/count fields remain null, even after restart.
Times are wall-clock Unix milliseconds, not active-study durations. Never infer an exact killed-session end or fill historical boundaries.
Room v11 preserves old state/log/detail rows; pre-tracking log rows have `sessionId=null` and no synthetic session.

Legacy exports have only `review_state.json`, `review_log.json` and `settings.json`, with no version manifest, log `id` fields or Grammar details.
They provide no Grammar evidence. V2 has five members and Grammar details, but no sessions or session links.
A v2/v3/v4/v5 export missing its required files or linked details is corruption, not legacy absence.
Listening encounters retain their own captured content and bind a non-null `reviewLogId` to one canonical Listening event.
Unscored study/discarded encounters have no event. A discarded Listening session may use `endReason="discarded"`.
Current playback `state` may change during feedback study; the submitted `response` cannot. First-encounter status is not inferred
from SRS absence. These timestamps are exposure markers, not proof that every sound was heard or every displayed word read.
The grammar analyzer checks the new member's basic array shape but does not audit or report listening response details.

Other modes still have no per-answer content revision or set ID. `sha:<source>@<branch>` settings give last-synced commit hints, but cannot establish
which snapshot a historical answer used (a session can retain questions loaded before a later sync).

`tools/analyze_grammar_progress.py` validates the canonical/detail join and emits local aggregates without response text or general settings.
It rejects duplicate JSON keys/ZIP members, incomplete v2 data, invalid types and mismatched/duplicate/orphan links rather than counting them
as evidence. It also validates v3-v5 session links and final counts without treating confirmations as extra SRS reviews.
V4 choice snapshots do not change these aggregates: the tool does not infer per-option mastery or independently regrade their text.
First-observed/repeated attempts use `(sourceId, stack, exerciseId)` in `(atMs, id)` order, still across sessions. Both recorded-ID views use the same
events; reused set-local IDs cannot be disambiguated from this export alone. This is an observability tool, not an import/restore feature or
proof of grammatical mastery or learning benefit.
