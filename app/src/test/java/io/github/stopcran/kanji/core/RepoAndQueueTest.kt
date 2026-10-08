package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RepoAndQueueTest {
    @Test
    fun extraPracticeOrdersOverdueUnseenThenUpcomingWithLimit() {
        val now = Instant.parse("2025-01-10T00:00:00Z")
        fun st(due: Instant) = SrsState(CardPhase.Review, 3.0, 5.0, due, now.minusSeconds(1000))
        val states = mapOf("A" to st(now.plusSeconds(900)), "B" to st(now.plusSeconds(100)), "C" to st(now.minusSeconds(5)))
        val q = QueueBuilder.extra(listOf("A", "B", "C", "D"), states, now).map { it.kanji }
        assertEquals(listOf("C", "D", "B", "A"), q)
        assertEquals(2, QueueBuilder.extra(listOf("A", "B", "C", "D"), states, now, limit = 2).size)
    }

    @Test
    fun parsesGitHubUrls() {
        val s = RepoSource.parse("https://github.com/stop-cran/learning-japanese", "main")!!
        assertEquals("stop-cran/learning-japanese", s.id)
        assertEquals("https://codeload.github.com/stop-cran/learning-japanese/zip/refs/heads/main", s.zipUrl)
        assertEquals("a/b", RepoSource.parse("https://github.com/A/B.git/", "dev/x")!!.id)
    }

    @Test
    fun rejectsOtherHostsAndOddBranches() {
        assertNull(RepoSource.parse("http://github.com/a/b", "main"))
        assertNull(RepoSource.parse("https://evil.com/a/b", "main"))
        assertNull(RepoSource.parse("https://github.com/a/b/tree/main", "main"))
        assertNull(RepoSource.parse("https://github.com/a/b", "../x"))
        assertNull(RepoSource.parse("https://github.com/a/b", "a b"))
    }

    @Test
    fun queueOrdersDueThenCapsNew() {
        val now = Instant.parse("2026-01-10T00:00:00Z")
        val states = mapOf(
            "A" to SrsState(CardPhase.Review, 3.0, 5.0, now.minusSeconds(100), now.minusSeconds(1000)),
            "B" to SrsState(CardPhase.Review, 3.0, 5.0, now.minusSeconds(500), now.minusSeconds(1000)),
            "C" to SrsState(CardPhase.Review, 3.0, 5.0, now.plusSeconds(500), now.minusSeconds(1000)),
        )
        val q = QueueBuilder.build(listOf("A", "B", "C", "D", "E", "F"), states, now, newCardsRemainingToday = 2)
        assertEquals(listOf("B", "A", "D", "E"), q.map { it.kanji })
    }
}
