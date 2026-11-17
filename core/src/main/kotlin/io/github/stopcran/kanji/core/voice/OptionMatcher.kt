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
        val variants = options.map { variants(it, language) }
        val originalScores = heard.map { hypothesis ->
            variants.map { choices -> choices.maxOfOrNull { score(hypothesis, it) } ?: 0.0 }
        }
        fun aggregate(scores: List<List<Double>>): List<Double> =
            options.indices.map { index -> scores.maxOf { it[index] } }
        val original = pick(aggregate(originalScores))
        if (original != null) return original

        val meanings = options.map { meanings(it, language) }
        val promotedScores = heard.mapIndexed { h, hypothesis ->
            options.indices.map { index ->
                if (matchesMeaningCombination(hypothesis, meanings[index])) 1.0 else originalScores[h][index]
            }
        }
        val promoted = pick(aggregate(promotedScores)) ?: return null
        if (originalScores.any { scores ->
                scores.indices.any { index ->
                    index != promoted &&
                        scores[index] >= MIN_SCORE && scores[index] - scores[promoted] >= MIN_MARGIN
                }
            }
        ) return null
        return promoted
    }

    private fun pick(scores: List<Double>): Int? {
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        val second = scores.indices.filter { it != best }.maxOfOrNull { scores[it] } ?: 0.0
        return best.takeIf { scores[it] >= MIN_SCORE && scores[it] - second >= MIN_MARGIN }
    }

    internal fun normalize(text: String, language: String = "en"): String {
        val russian = language == "ru"
        val words = if (russian) russianNumberWords else numberWords
        val tokens = withoutQualifiers(text).lowercase()
            .replace('ё', 'е')
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
    internal fun variants(option: String, language: String = "en"): List<String> {
        val title = withoutQualifiers(option)
        return (listOf(normalize(title, language)) + meanings(title, language)).filter { it.isNotEmpty() }.distinct()
    }

    private fun meanings(option: String, language: String): List<String> =
        withoutQualifiers(option).split(Regex(";|,\\s")).map { normalize(it, language) }.filter { it.isNotEmpty() }

    private fun withoutQualifiers(text: String): String = buildString {
        var depth = 0
        for (char in text) {
            when {
                char == '(' -> {
                    if (depth == 0) append(' ')
                    depth++
                }
                char == ')' && depth > 0 -> depth--
                depth == 0 -> append(char)
            }
        }
    }

    private fun matchesMeaningCombination(heard: String, meanings: List<String>): Boolean {
        if (meanings.size < 2) return false
        val tokens = heard.split(' ')
        val parts = meanings.map { it.split(' ') }
        data class State(val previous: Int, val first: Int, val distinct: Boolean)
        val reached = Array(tokens.size + 1) { mutableSetOf<State>() }
        reached[0].add(State(-1, -1, false))
        for (start in tokens.indices) {
            for ((index, part) in parts.withIndex()) {
                val end = start + part.size
                if (end > tokens.size || tokens.subList(start, end) != part) continue
                for (state in reached[start]) {
                    if (index <= state.previous) continue
                    val first = if (state.first < 0) index else state.first
                    val distinct = state.distinct || parts[first] != part
                    if (distinct && end == tokens.size) return true
                    reached[end].add(State(index, first, distinct))
                }
            }
        }
        return false
    }

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
