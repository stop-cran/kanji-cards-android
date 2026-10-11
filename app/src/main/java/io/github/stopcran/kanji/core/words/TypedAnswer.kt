package io.github.stopcran.kanji.core.words

import io.github.stopcran.kanji.core.reading.ReadingKey
import java.text.Normalizer

/** How a typed answer relates to the asked word. */
enum class TypedVerdict {
    /** The written form of the word, or of a same-meaning word (a synonym). */
    Exact,

    /** The word's reading in kana where the word has kanji: right word, kanji not recalled. */
    KanaOnly,

    /** Anything else, including a different known word ([matched]). */
    Wrong,
}

data class TypedResult(val verdict: TypedVerdict, val matched: WordCard? = null) {
    val ok: Boolean get() = verdict != TypedVerdict.Wrong
}

/**
 * Grades an answer to "English -> type the Japanese word". The keyboard's conversion is accepted as typed. A synonym is
 * another word with the same English title and type, so a valid alternative is not marked wrong.
 */
object TypedAnswer {
    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).filter { !it.isWhitespace() }

    private fun kana(s: String) = ReadingKey.fold(normalize(s))

    fun grade(input: String, target: WordCard, all: List<WordCard>): TypedResult {
        val typed = normalize(input)
        if (typed.isEmpty()) return TypedResult(TypedVerdict.Wrong)
        val kanaWord = target.word == target.reading
        if (typed == normalize(target.word) || (kanaWord && kana(typed) == kana(target.word))) return TypedResult(TypedVerdict.Exact, target)
        if (!kanaWord && kana(typed) == kana(target.reading)) return TypedResult(TypedVerdict.KanaOnly, target)
        val known = all.firstOrNull { it.word != target.word && normalize(it.word) == typed }
        val synonym = known?.takeIf { it.title == target.title && it.type == target.type }
        return if (synonym != null) TypedResult(TypedVerdict.Exact, synonym) else TypedResult(TypedVerdict.Wrong, known)
    }
}
