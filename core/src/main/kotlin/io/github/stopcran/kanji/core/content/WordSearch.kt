package io.github.stopcran.kanji.core.content

/** Mixed search over a word's written form, kana reading (either script), romaji and English title. */
object WordSearch {
    private const val ROMAJI_PENALTY = 20

    /** 0 = no match; higher is a better match. An empty query matches everything equally. */
    fun score(word: String, reading: String, title: String, query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 1
        var best = 0
        if (word.contains(q)) best = if (word == q) 100 else 95
        fun scoreKana(kana: String, penalty: Int) {
            val full = CardSearch.toHiragana(reading)
            val s = when {
                full == kana -> 90
                full.startsWith(kana) -> 70
                full.contains(kana) -> 50
                else -> 0
            }
            if (s > 0) best = maxOf(best, s - penalty)
        }
        val qk = CardSearch.toHiragana(q.lowercase())
        if (qk.any { it in '\u3041'..'\u3096' }) scoreKana(qk, 0)
        Romaji.toHiragana(q)?.let { scoreKana(it, ROMAJI_PENALTY) }
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
