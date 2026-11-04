package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.isOrdinaryEntryAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeContentStateTest {
    @Test
    fun cachedRussianPilotCannotEnableOrdinaryRussianDefaultEntry() {
        val source = RepoSource("stop-cran", "learning-japanese-ru", "main")
        val cached = HomeContentState(ready = true, activity = null, rolloutBlocked = true)

        assertFalse(cached.usable)
        assertFalse(cached.retryable)
        assertFalse(isOrdinaryEntryAllowed(source))
        assertTrue(isOrdinaryEntryAllowed(source.copy(branch = "ru-pilot-a")))
        assertTrue(isOrdinaryEntryAllowed(RepoSource("example", "english", "main")))
    }
}
