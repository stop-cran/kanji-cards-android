package io.github.stopcran.kanji.core.voice

/**
 * Chooses which of the displayed quiz options a spoken phrase refers to. The set is closed, so only the shown options are
 * compared; an unclear or ambiguous phrase yields no result rather than a guess.
 */
object OptionMatcher {
    private const val MIN_SCORE = 0.8
    private const val MIN_MARGIN = 0.1
    private const val SUBSET_SCORE = 0.85

    private val numberWords = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    private val russianNumberWords = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять")
    private val russianNumberForms = mapOf(
        "одна" to "один", "одно" to "один", "одну" to "один",
        "две" to "два", "двух" to "два",
    )
    private val articles = setOf("to", "a", "an", "the")

    /** Index of the option the hypotheses (best first) clearly refer to, or null. */
    fun match(hypotheses: List<String>, options: List<String>, language: String = "en"): Int? {
        val heard = hypotheses.map { normalize(it, language) }.filter { it.isNotEmpty() }
        if (heard.isEmpty() || options.isEmpty()) return null
        val scores = options.map { option ->
            val variants = variants(option, language)
            if (variants.isEmpty()) 0.0 else heard.maxOf { h -> variants.maxOf { v -> score(h, v) } }
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        val second = scores.indices.filter { it != best }.maxOfOrNull { scores[it] } ?: 0.0
        return best.takeIf { scores[it] >= MIN_SCORE && scores[it] - second >= MIN_MARGIN }
    }

    internal fun normalize(text: String, language: String = "en"): String {
        val russian = language == "ru"
        val words = if (russian) russianNumberWords else numberWords
        val tokens = text.lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
            .map { token ->
                when {
                    token.toIntOrNull()?.let { it in words.indices } == true -> words[token.toInt()]
                    russian -> russianNumberForms[token] ?: token
                    else -> token
                }
            }
        val trimmed = if (!russian && tokens.size > 1 && tokens.first() in articles) tokens.drop(1) else tokens
        return trimmed.joinToString(" ")
    }

    /** The whole title plus each part of a multi-meaning title ("up, above", "cooked rice; meal"). */
    internal fun variants(option: String, language: String = "en"): List<String> =
        (listOf(option) + option.split(Regex(";|,\\s")))
            .map { normalize(it, language) }.filter { it.isNotEmpty() }.distinct()

    internal fun score(heard: String, variant: String): Double {
        if (heard == variant) return 1.0
        val h = heard.split(' ').toSet()
        val v = variant.split(' ').toSet()
        if (h.containsAll(v) || v.containsAll(h)) return SUBSET_SCORE
        val longest = maxOf(heard.length, variant.length)
        if (longest < 4) return 0.0
        return 1.0 - editDistance(heard, variant).toDouble() / longest
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
