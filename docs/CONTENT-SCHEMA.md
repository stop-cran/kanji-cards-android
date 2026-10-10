# Content schema

The authoritative content format lives with the content: [docs/content-format.md](https://github.com/stop-cran/learning-japanese/blob/main/docs/content-format.md)
in the default repo `stop-cran/learning-japanese`.

What the app enforces when importing (`core/content/ContentParser.kt`, `FrontMatter.kt`, `SafeZip.kt`):

- The repo archive (`codeload.github.com/<owner>/<repo>/zip/refs/heads/<branch>`) must contain `manifest.json` with `schemaVersion` 1.
- Only `manifest.json` and files directly in `kanji/`, `words/`, `articles/`, `strokes/` are read; zip-slip paths, more than 5,000 entries,
  files over 2 MB or 50 MB in total are rejected.
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
- Review state is keyed by (content source `owner/repo` lowercased, stack, kanji or word, mode), so content edits never reset scheduling;
  pointing the app at a different repository or studying a different stack uses a separate set of review states. Changing a word's level
  changes stack membership, not its identity; state in its previous stack is retained, not copied to other stacks.

The unreleased Room database version 3 adds nullable `words.jlpt` and non-null `words.quizExclusions` (default empty string, using the existing
list separator). The version 2-to-3 migration keeps all content, word IDs, review states and review logs; existing rows initially have no explicit
level or exclusions. It clears only sync metadata so normal home-screen sync reimports even an unchanged content revision and fills in metadata
that older app versions ignored. Cached content remains usable with legacy behavior while offline. Version 1 databases upgrade through both
registered migrations. The content manifest remains schema version 1; regenerate hashes and `contentVersion` when changing word metadata.
`contentVersion` is the update contract: a new commit with an unchanged `contentVersion` is treated as up to date and not imported.

`DatabaseMigrationTest` uses Robolectric with native SQLite to check Room read/write round trips across a database reopen, upgrades from
versions 1 and 2, and content replacement without losing existing review state or history.
