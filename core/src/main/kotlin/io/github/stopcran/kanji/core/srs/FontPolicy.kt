package io.github.stopcran.kanji.core.srs

import kotlin.random.Random

enum class KanjiFont {
    /** System default CJK face (Noto Sans CJK on Android): no download needed. */
    Gothic,
    Mincho,
    Textbook,
    Brush,
}

/**
 * Progressive font variety: a card is shown in plainer faces while it is young and in more
 * stylised ones as its memory stability grows. A lapse resets stability, so the card drops back.
 */
object FontPolicy {
    fun allowed(stabilityDays: Double): List<KanjiFont> = when {
        stabilityDays < 4.0 -> listOf(KanjiFont.Gothic)
        stabilityDays < 12.0 -> listOf(KanjiFont.Gothic, KanjiFont.Mincho)
        stabilityDays < 30.0 -> listOf(KanjiFont.Gothic, KanjiFont.Mincho, KanjiFont.Textbook)
        else -> KanjiFont.entries
    }

    /** Never-reviewed cards (stability 0) always get the plain face. */
    fun pick(stabilityDays: Double, random: Random, previous: KanjiFont? = null): KanjiFont {
        val options = allowed(stabilityDays)
        val fresh = options.filter { it != previous }.ifEmpty { options }
        return fresh[random.nextInt(fresh.size)]
    }
}
