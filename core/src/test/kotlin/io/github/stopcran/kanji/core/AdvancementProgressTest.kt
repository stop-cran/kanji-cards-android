package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.Advancement
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancementProgressTest {
    private val now = Instant.parse("2026-01-10T00:00:00Z")
    private val solid = SrsState(CardPhase.Review, 7.0, 5.0, now, now, 2)

    @Test
    fun progressCountsOnlySolidStatesAndKeepsSkillsSeparate() {
        val progress = Advancement.progress(
            mapOf(
                "Choose" to listOf(solid, null, solid.copy(phase = CardPhase.Learning)),
                "Type" to listOf(solid, solid.copy(stability = 6.99)),
                "Unavailable" to emptyList(),
            ),
            now,
        )

        assertEquals(1, progress.skills.getValue("Choose").solid)
        assertEquals(3, progress.skills.getValue("Choose").total)
        assertEquals(1, progress.skills.getValue("Type").solid)
        assertEquals(50, progress.skills.getValue("Type").percent)
        assertFalse("Empty skills are not invented as a denominator", "Unavailable" in progress.skills)
        assertEquals(2, progress.total.solid)
        assertEquals(5, progress.total.total)
    }

    @Test
    fun emptyInventoryHasNoPercentage() {
        val progress = Advancement.progress(mapOf("Writing" to emptyList()), now)

        assertEquals(0, progress.total.total)
        assertNull(progress.total.percent)
    }

    @Test
    fun readinessRequiresMinimumEvidenceAndBothShareThresholds() {
        fun states(solidCount: Int, total: Int): List<SrsState?> =
            List(total) { if (it < solidCount) solid else null }
        fun ready(items: Map<String, List<SrsState?>>) = Advancement.ready(Advancement.progress(items, now))

        assertFalse(ready(mapOf("Choose" to states(19, 19))))
        assertFalse(ready(mapOf("Choose" to states(16, 20))))
        assertFalse(
            ready(mapOf("Choose" to states(15, 15), "Type" to states(2, 5))),
        )
        assertTrue(
            ready(mapOf("Choose" to states(14, 15), "Type" to states(3, 5))),
        )
    }
}
