# Bundled rare-glyph font

`app/src/main/res/font/rare_han_a.otf` and `rare_han_b.otf` are subsets (about ten glyphs) of
HanaMinA / HanaMinB 8.030 (Hanazono Mincho AFDKO build, https://github.com/cjkvi/HanaMinAFDKO),
renamed to "Kanji Rare Subset". Copyright 2007-2018 GlyphWiki. The font's licence is the
GlyphWiki licence (http://glyphwiki.org/license.html), as stated in the upstream README.

**Verify the licence terms before a Google Play release**; the licence page was unreachable when this was added.

Regenerate with `python tools/make_rare_font.py <content-repo> <dir with HanaMinA.otf, HanaMinB.otf>`.
