package io.github.stopcran.kanji.core.content

/** Mixed search over kanji, English title and readings (kana in either script; okurigana dots and affix dashes ignored). */
object CardSearch {
    fun toHiragana(s: String): String =
        s.map { if (it in '\u30A1'..'\u30F6') (it.code - 0x60).toChar() else it }.joinToString("")

    private fun normalizeReading(r: String) = toHiragana(r).filter { it != '.' && it != '-' }

    /** 0 = no match; higher is a better match. */
    fun score(kanji: String, title: String, readings: List<String>, query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 1
        var best = 0
        if (q.contains(kanji)) best = 100
        val qk = normalizeReading(q.lowercase())
        if (qk.isNotEmpty()) {
            for (r in readings) {
                val full = normalizeReading(r)
                val stem = toHiragana(r.substringBefore('.').trimStart('-'))
                best = maxOf(
                    best,
                    when {
                        full == qk || stem == qk -> 90
                        full.startsWith(qk) -> 70
                        full.contains(qk) -> 50
                        else -> 0
                    },
                )
            }
        }
        val ql = q.lowercase()
        val words = title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        best = maxOf(
            best,
            when {
                words.any { it == ql } -> 80
                words.any { it.startsWith(ql) } -> 60
                title.lowercase().contains(ql) -> 40
                else -> 0
            },
        )
        return best
    }
}
