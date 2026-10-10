package io.github.stopcran.kanji.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import io.github.stopcran.kanji.R

/**
 * Rare CJK characters (old phonetic components etc.) that phone system fonts often cannot draw.
 * They are rendered from a bundled subset of Hanazono Mincho; see tools/make_rare_font.py and third_party/fonts.
 */
object RareHan {
    private val bmp by lazy { FontFamily(Font(R.font.rare_han_a)) }
    private val plane2 by lazy { FontFamily(Font(R.font.rare_han_b)) }

    // Keep in sync with RANGES in tools/make_rare_font.py.
    fun isRare(cp: Int): Boolean =
        cp in 0x2E80..0x2FDF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF || cp in 0x20000..0x2FFFF

    fun family(cp: Int): FontFamily = if (cp >= 0x20000) plane2 else bmp

    /** Splits [text] into runs; rare characters get the bundled font. */
    fun AnnotatedString.Builder.appendWithRare(text: String) {
        var i = 0
        var plainStart = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            if (isRare(cp)) {
                append(text.substring(plainStart, i))
                withStyle(SpanStyle(fontFamily = family(cp))) { append(text.substring(i, i + len)) }
                plainStart = i + len
            }
            i += len
        }
        append(text.substring(plainStart))
    }
}
