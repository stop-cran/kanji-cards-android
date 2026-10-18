# Content schema

The authoritative content format lives with the content: [docs/content-format.md](https://github.com/stop-cran/learning-japanese/blob/main/docs/content-format.md)
in the default repo `stop-cran/learning-japanese`.

What the app enforces when importing (`core/content/ContentParser.kt`, `FrontMatter.kt`, `SafeZip.kt`):

- The repo archive (`codeload.github.com/<owner>/<repo>/zip/refs/heads/<branch>`) must contain `manifest.json` with `schemaVersion` 1.
- Only `manifest.json` and files directly in `kanji/`, `words/`, `articles/`, `strokes/`, `counters/`, `grammar/` are read, plus `images/*.webp`
  (at most 150 KiB each). Zip-slip paths, more than 8,000 entries, wanted text files over 2 MiB or wanted data over 50 MiB are rejected.
  The separate 100 MiB inflated limit includes skipped entries too.
- Images are listed in the manifest's separate `images` map with SHA-256 over the raw bytes (no LF normalisation). A word's `image` and
  `image_scene` keys are read together; an image that is missing, oversize, not WebP, hash-mismatched or shared by two words is reported as a
  problem and only that word's picture is dropped.
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

## Room v9 and content replacement

The current Room version is **9**. The 7-to-8 migration creates `grammar_sets` (key: source + set ID) and `grammar_responses`
(key: `reviewLogId`) and clears only sync metadata to force a content reimport. Existing content, review states and history are preserved;
all older versions use the registered migration chain, with no destructive fallback.
The additive 8-to-9 migration preserves that snapshot and all history, creates source-keyed `grammar_focus` (`revision`, cursor JSON),
and adds nullable `sync_meta.grammarProblem`. Atomic replacement invalidates outstanding focus tickets and stores snapshot diagnostics.

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

`DatabaseMigrationTest` checks upgrades from versions 1, 2, 3, 7 and 8, native-SQLite reopen preservation and the Grammar event/export join.
`ContentSyncTest` checks a changed Grammar snapshot without losing its existing progress or response history.
`GrammarFocusPersistenceTest` covers reservation/reopen, stale tickets, rollback and source isolation. Cursor updates share the answer's
state/log/detail transaction. The cursor retains automatic ordinal, manual selection nonce, active group/origin/seed and recorded exposure
IDs with their facet/context keys. It is omitted from progress ZIPs; Android backup includes the database and preferences normally.

## Progress export v2

**Settings → Export progress** writes five UTF-8 JSON members:

| ZIP member | Meaning |
| --- | --- |
| `export_manifest.json` | `{"formatVersion":2}`; independent of content JSON/manifest schema versions. |
| `review_state.json` | Current SRS rows: `sourceId`, `stack`, `kanji`, `mode`, `reading`, `phase`, `stability`, `difficulty`, `dueMs`, `lastReviewMs`, `reps`, `lapses`. |
| `review_log.json` | Canonical recorded events: `id`, `sourceId`, `stack`, `kanji`, `mode`, `reading`, `grade`, `atMs` (Unix milliseconds). Grades: Again=1, Hard=2, Good=3, Easy=4. |
| `grammar_responses.json` | `reviewLogId`, `sourceId`, `stack`, `exerciseId`, `constructionId`, `relationshipId`, `promptType` (`choose`/`type`), `response`, `correct`. |
| `settings.json` | Stored preferences, including source configuration and cached sync metadata. |

For a recorded Grammar answer, state, canonical log and response detail are written in one transaction. Join `reviewLogId` to the unique
log `id` for grade/time; source, stack and exercise identity must also match, with canonical mode `Grammar`. Choice `response` is an option
ID, typing `response` is normalized text, and give-up records an empty string. Details record the exercise's target construction and
relationship, not the wrong choice's construction. There is no separate guessed flag: the current UI's correct-choice "I guessed" yields
Hard. Successful practice confirmations create no state update, log or detail, even though the session's displayed tally includes them.
The export's answer count is the number of canonical log rows, not log rows plus details.

Legacy exports have only `review_state.json`, `review_log.json` and `settings.json`, with no version manifest, log `id` fields or Grammar details.
They provide no Grammar evidence; a v2 export missing required files or linked details is corruption, not legacy absence.
The export has no per-answer content revision or set ID. `sha:<source>@<branch>` settings give last-synced commit hints, but cannot establish
which snapshot a historical answer used (a session can retain questions loaded before a later sync).

`tools/analyze_grammar_progress.py` validates the canonical/detail join and emits local aggregates without response text or general settings.
It rejects duplicate JSON keys/ZIP members, incomplete v2 data, invalid types and mismatched/duplicate/orphan links rather than counting them
as evidence. First-observed/repeated attempts use `(sourceId, stack, exerciseId)` in `(atMs, id)` order. Both recorded-ID views use the same
events; reused set-local IDs cannot be disambiguated from this export alone. This is an observability tool, not an import/restore feature or
proof of grammatical mastery or learning benefit.
