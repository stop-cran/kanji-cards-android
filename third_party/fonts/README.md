# Bundled fonts

## Quiz fonts (SIL Open Font License 1.1)

| File | Font | Licence text |
|---|---|---|
| `app/src/main/res/font/kanji_mincho.ttf` | Noto Serif JP, Copyright 2012 Google Inc. | `OFL-notoserifjp.txt` |
| `app/src/main/res/font/kanji_textbook.ttf` | Klee One, Copyright 2020 The Klee Project Authors | `OFL-kleeone.txt` |
| `app/src/main/res/font/kanji_brush.ttf` | Yuji Syuku, Copyright 2021 The Yuji Project Authors | `OFL-yujisyuku.txt` |

These are **modified versions** (subset to JIS X 0208 level 1 and ASCII, instances of variable fonts fixed at one weight, hinting and layout
features removed; see the README "Fonts" section for the command). The OFL allows this, including bundling in an app, as long as the
copyright notice and licence text travel with the font (they do: the three `OFL-*.txt` files, and the About screen).

**Reserved Font Names:** none of the three copyright statements declares a Reserved Font Name (the licence text only defines the term), so the
subsets may keep their original family names. If an upstream release adds one, rename the subset before shipping.

## Rare-glyph font (GlyphWiki licence)

`app/src/main/res/font/rare_han_a.otf` and `rare_han_b.otf` are small subsets of
HanaMinA / HanaMinB 8.030 (Hanazono Mincho AFDKO build, https://github.com/cjkvi/HanaMinAFDKO/releases/tag/8.030),
renamed to "Kanji Rare Subset". Copyright 2007-2018 GlyphWiki. The font's licence is the
GlyphWiki licence, as stated in the [pinned upstream README](https://github.com/cjkvi/HanaMinAFDKO/blob/8.030/README.md#licence).
It is credited in the About screen, and the original embedded copyright/licence records are preserved.

**Licence verification (2026-10-27):** [GlyphWiki-license.txt](GlyphWiki-license.txt) preserves the Japanese original and its dated
English translation from [Mozilla's copy](https://github.com/mozilla-firefox/firefox/blob/d9098b3e11c2fbc2b2d84e53a3c784814cbdab3e/layout/reftests/fonts/glyphwiki-license.txt).
The terms explicitly permit reproduction, modification, and using the glyphs in new fonts and published works, without author-attribution
restrictions; the Japanese original takes precedence. This verification uses the pinned font project's own licence linkage and the actual
bilingual terms, not an assumption that this release is SIL OFL. The live GlyphWiki wiki pages returned HTTP 403 and the old
`license.html` endpoint returned HTTP 404, so the primary page was not fetched directly.

Source font SHA-256 values for the pinned release assets:

- `HanaMinA.otf`: `f068fbe4f7e091828d23dc833f9438aebe0446ce341b69497fbb514affaee190`
- `HanaMinB.otf`: `cfec2d1577f15b4bb9fbeab956b03d3fc6317ee97da3ab1515889cb5ce4fd46e`

Regenerate with `python tools/make_rare_font.py <content-repo> <dir with HanaMinA.otf, HanaMinB.otf>`.
