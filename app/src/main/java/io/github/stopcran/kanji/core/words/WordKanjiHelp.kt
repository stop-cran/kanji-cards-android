package io.github.stopcran.kanji.core.words

/** UTF-16 ranges include attached marks; null levels mean unclassified, not advanced. */
data class WordKanjiRange(val start: Int, val end: Int, val level: Int?)

data class WordKanjiHelp(val word: String, val reading: String, val ranges: List<WordKanjiRange>) {
    companion object {
        fun build(word: String, reading: String, stack: WordStack, levels: Map<String, Int?>): WordKanjiHelp? {
            if (stack.minLevel == null || stack.tag != null) return null
            val ranges = mutableListOf<WordKanjiRange>()
            var start = 0
            while (start < word.length) {
                val point = word.codePointAt(start)
                val baseEnd = start + Character.charCount(point)
                var end = baseEnd
                while (end < word.length && Character.getType(word.codePointAt(end)) in markTypes) {
                    end += Character.charCount(word.codePointAt(end))
                }
                if (Character.isIdeographic(point)) {
                    val level = levels[word.substring(start, baseEnd)]
                    if (level == null || !stack.contains(level)) ranges += WordKanjiRange(start, end, level)
                }
                start = end
            }
            return if (ranges.isEmpty()) null else WordKanjiHelp(word, reading, ranges)
        }

        private val markTypes = setOf(
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
        )
    }
}
