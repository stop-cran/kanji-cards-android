package io.github.stopcran.kanji.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.srs.KanjiFont

private val mincho = FontFamily(Font(R.font.kanji_mincho))
private val textbook = FontFamily(Font(R.font.kanji_textbook))
private val brush = FontFamily(Font(R.font.kanji_brush))

/** Bundled subsets (JIS X 0208 level 1 + ASCII); glyphs outside the subset fall back to the system CJK face. */
fun KanjiFont.family(): FontFamily = when (this) {
    KanjiFont.Gothic -> FontFamily.Default
    KanjiFont.Mincho -> mincho
    KanjiFont.Textbook -> textbook
    KanjiFont.Brush -> brush
}
