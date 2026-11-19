package io.github.stopcran.kanji.core.readingtext

import io.github.stopcran.kanji.core.content.CardSearch

data class ReadingRubyPart(val text: String, val reading: String? = null)

/** Kana anchors separate reviewed whole-word readings without guessing individual kanji readings. */
object ReadingFurigana {
    fun parts(text: String, reading: String): List<ReadingRubyPart> {
        val plain = listOf(ReadingRubyPart(text))
        val runs = runs(text)
        val first = runs.indexOfFirst { isHan(it.codePointAt(0)) }
        if (first < 0) return plain
        val last = runs.indexOfLast { isHan(it.codePointAt(0)) }
        val folded = CardSearch.toHiragana(reading)
        val prefix = CardSearch.toHiragana(runs.take(first).joinToString(""))
        val suffix = CardSearch.toHiragana(runs.drop(last + 1).joinToString(""))
        if (!folded.startsWith(prefix) || !folded.endsWith(suffix)) return plain
        var cursor = prefix.length
        val limit = reading.length - suffix.length
        val result = mutableListOf<ReadingRubyPart>()
        if (prefix.isNotEmpty()) result += ReadingRubyPart(runs.first())
        for (index in first..last step 2) {
            val end = if (index == last) limit else {
                val anchor = CardSearch.toHiragana(runs[index + 1])
                val position = folded.indexOf(anchor, cursor + 1)
                // Ambiguous anchors keep the whole occurrence plain; lookup still exposes its full reading.
                if (position < 0 || position >= limit || folded.indexOf(anchor, position + 1) in 0 until limit) return plain
                position
            }
            if (end <= cursor) return plain
            result += ReadingRubyPart(runs[index], reading.substring(cursor, end))
            if (index < last) {
                result += ReadingRubyPart(runs[index + 1])
                cursor = end + runs[index + 1].length
            }
        }
        if (suffix.isNotEmpty()) result += ReadingRubyPart(runs.last())
        return result
    }

    private fun isHan(point: Int) = Character.isIdeographic(point) || point == 0x3005 || point == 0x3006

    private fun runs(text: String): List<String> {
        val runs = mutableListOf<String>()
        text.codePoints().forEach { point ->
            val character = String(Character.toChars(point))
            if (runs.isNotEmpty() && isHan(runs.last().codePointAt(0)) == isHan(point)) {
                runs[runs.lastIndex] += character
            } else runs += character
        }
        return runs
    }
}
