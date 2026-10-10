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

`app/src/main/res/font/rare_han_a.otf` and `rare_han_b.otf` are subsets (about ten glyphs) of
HanaMinA / HanaMinB 8.030 (Hanazono Mincho AFDKO build, https://github.com/cjkvi/HanaMinAFDKO),
renamed to "Kanji Rare Subset". Copyright 2007-2018 GlyphWiki. The font's licence is the
GlyphWiki licence (http://glyphwiki.org/license.html), as stated in the upstream README. It is credited in the About screen.

**Open item before a Google Play release:** the licence page could not be fetched when this was written (HTTP 403 from the site), so its exact
terms are unverified. Read it in a browser; if redistribution of subsets is not clearly allowed, drop `RareHan` (articles fall back to the system font).

Regenerate with `python tools/make_rare_font.py <content-repo> <dir with HanaMinA.otf, HanaMinB.otf>`.
