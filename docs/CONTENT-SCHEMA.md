# Content repo schema (v1, draft)

Layout of a content repo (default: `stop-cran/learning-japanese`):

```
manifest.json        generated; schemaVersion, contentVersion, file list with hashes
kanji/<char>.md      one card per kanji, e.g. kanji/怪.md
words/<word>.md      common words covering on'yomi/kun'yomi readings
articles/<slug>.md   free-form articles, e.g. articles/ayashii-and-related-words.md
strokes/<char>.json  generated from KanjiVG (stroke paths in order)
```

## Kanji card

YAML front matter + Markdown body (the article shown on "details").

```markdown
---
kanji: 怪
title: strange, suspicious     # shown in the question state of both modes
jlpt: 1                        # 1-5, optional
tags: [jlpt-n1, grade-S]       # free tags; stacks are filters over tags
onyomi: [カイ]
kunyomi: [あや.しい]
distractors: [妖, 奇]          # optional; similar kanji for quiz options
---
Article body. Links are relative: [怪しい](../words/怪しい.md),
[difference](../articles/ayashii-and-related-words.md).
```

## Word card

```markdown
---
word: 怪しい
reading: あやしい
meaning: suspicious
kanji: [怪]
---
```

## Quiz answers

- `title` is the canonical, machine-gradeable answer shown as an option; add optional `meanings: [...]` for extra detail.
- Each `distractors` entry must resolve to an existing kanji card; options are that card's `title`.
- Options must be unique after normalization (case/whitespace); the validator rejects kanji sharing a `title` unless they are never offered together (use distinct titles).

## Strokes file (`strokes/<char>.json`, TODO: finalize before generator/matcher)

Generator-produced, versioned, not raw SVG: `{ "schemaVersion": 1, "source": "KanjiVG <version>", "viewBox": [0,0,109,109],
"strokes": [ { "id": 1, "points": [[x,y], ...] } ] }` with strokes in order and points as sampled polylines in the fixed viewBox.

## Rules

- Relative `.md` links only (plus `https`); no raw HTML; the app rewrites links to in-app navigation.
- The kanji character is the stable ID. Review state is keyed by `(repo, kanji, mode)`.
- A CI validator in the content repo checks front matter, duplicate kanji, broken links and strokes presence.
- `manifest.json` lets the app sync via diff; the app downloads a branch zip archive, not a git clone.
