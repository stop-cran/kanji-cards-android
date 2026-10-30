package io.github.stopcran.kanji.core.content

/** Mixed search over kanji, English title and readings (kana in either script; okurigana dots and affix dashes ignored). */
object CardSearch {
    private const val ROMAJI_PENALTY = 20

    fun toHiragana(s: String): String =
        s.map { if (it in '\u30A1'..'\u30F6') (it.code - 0x60).toChar() else it }.joinToString("")

    private fun normalizeReading(r: String) = toHiragana(r).filter { it != '.' && it != '-' }

    /**
     * 0 = no match; higher is a better match. A katakana query prefers on'yomi and a hiragana query prefers kun'yomi
     * (the dictionary convention); readings of the other kind still match, but rank lower.
     */
    fun score(kanji: String, title: String, onyomi: List<String>, kunyomi: List<String>, query: String): Int {
        val q = query.trim()
        if (q.isEmpty()) return 1
        var best = 0
        if (q.contains(kanji)) best = 100
        val qk = normalizeReading(q.lowercase())
        if (qk.isNotEmpty()) {
            val prefersOn = q.any { it in '\u30A1'..'\u30F6' } && q.none { it in '\u3041'..'\u3096' }
            val prefersKun = q.any { it in '\u3041'..'\u3096' } && q.none { it in '\u30A1'..'\u30F6' }
            fun scoreReadings(readings: List<String>, preferred: Boolean) {
                val penalty = if (preferred) 0 else 30
                for (r in readings) {
                    val full = normalizeReading(r)
                    val stem = toHiragana(r.substringBefore('.').trimStart('-'))
                    val s = when {
                        full == qk || stem == qk -> 90
                        full.startsWith(qk) -> 70
                        full.contains(qk) -> 50
                        else -> 0
                    }
                    if (s > 0) best = maxOf(best, s - penalty)
                }
            }
            scoreReadings(onyomi, !prefersKun)
            scoreReadings(kunyomi, !prefersOn)
        }
        // Romaji is script-neutral: it finds on and kun readings alike, but ranks below a kana query of the matching script.
        Romaji.toHiragana(q)?.takeIf { it != qk }?.let { kana ->
            for (r in onyomi + kunyomi) {
                val full = normalizeReading(r)
                val stem = toHiragana(r.substringBefore('.').trimStart('-'))
                val s = when {
                    full == kana || stem == kana -> 90
                    full.startsWith(kana) -> 70
                    full.contains(kana) -> 50
                    else -> 0
                }
                if (s > 0) best = maxOf(best, s - ROMAJI_PENALTY)
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
