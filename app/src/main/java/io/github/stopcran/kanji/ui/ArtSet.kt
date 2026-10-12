package io.github.stopcran.kanji.ui

import androidx.annotation.DrawableRes

/** Illustration resources for the home entries; null draws a plain card. Light and dark variants share a name (drawable-night). */
class ArtSet(@DrawableRes val kanji: Int?, @DrawableRes val words: Int?, @DrawableRes val counters: Int?) {
    companion object {
        val current = ArtSet(null, null, null)
    }
}
