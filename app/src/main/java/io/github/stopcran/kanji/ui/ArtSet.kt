package io.github.stopcran.kanji.ui

import androidx.annotation.DrawableRes
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.data.Settings
import kotlin.random.Random

/** Illustration resources for the home entries. Light and dark variants share a name (drawable-night). */
class ArtSet(
    @DrawableRes val kanji: Int?,
    @DrawableRes val words: Int?,
    @DrawableRes val counters: Int?,
    @DrawableRes val grammar: Int?,
    @DrawableRes val listening: Int?,
) {
    companion object {
        val all = listOf(
            ArtSet(R.drawable.art1_kanji, R.drawable.art1_words, R.drawable.art1_counters, R.drawable.art1_grammar, R.drawable.art1_listening),
            ArtSet(R.drawable.art2_kanji, R.drawable.art2_words, R.drawable.art2_counters, R.drawable.art2_grammar, R.drawable.art2_listening),
            ArtSet(R.drawable.art3_kanji, R.drawable.art3_words, R.drawable.art3_counters, R.drawable.art3_grammar, R.drawable.art3_listening),
            ArtSet(R.drawable.art4_kanji, R.drawable.art4_words, R.drawable.art4_counters, R.drawable.art4_grammar, R.drawable.art4_listening),
        )

        /** The set for this process; null entries draw a plain card. */
        @Volatile
        var current: ArtSet = all.first()
            private set

        /** Picks a random set that differs from the previous session's and remembers it. */
        fun choose(settings: Settings, random: Random = Random.Default) {
            val index = ArtRotation.next(all.size, settings.artSetLast, random)
            settings.artSetLast = index
            current = all[index]
        }
    }
}
