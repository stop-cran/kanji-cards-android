# Content schema

The authoritative content format lives with the content: [docs/content-format.md](https://github.com/stop-cran/learning-japanese/blob/main/docs/content-format.md)
in the default repo `stop-cran/learning-japanese`.

What the app enforces when importing (`core/content/ContentParser.kt`, `SafeZip.kt`):

- The repo archive (`codeload.github.com/<owner>/<repo>/zip/refs/heads/<branch>`) must contain `manifest.json` with `schemaVersion` 1.
- Only `manifest.json` and files directly in `kanji/`, `words/`, `articles/`, `strokes/` are read; zip-slip paths, more than 5,000 entries,
  files over 2 MB or 50 MB in total are rejected.
- Every file listed in the manifest must match its SHA-256 (computed over LF-normalised bytes); mismatching, missing or malformed files are
  skipped and counted as problems instead of failing the sync. Duplicate kanji titles are rejected (titles are quiz answers).
- Front matter is a restricted subset: `key: value` scalars and inline `[a, b]` lists.
- Review state is keyed by (content source `owner/repo` lowercased, kanji, mode), so content edits never reset scheduling; pointing the app at a
  different repository starts a separate set of review states.
