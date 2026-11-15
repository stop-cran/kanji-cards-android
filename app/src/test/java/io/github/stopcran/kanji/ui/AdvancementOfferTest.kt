package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.srs.ProgressCount
import io.github.stopcran.kanji.core.srs.StudyProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AdvancementOfferTest {
    private val now = Instant.parse("2026-01-10T00:00:00Z")
    private val target = StudyProgress(mapOf("next" to ProgressCount(0, 1)))

    @Test
    fun thresholdRequiresOverallAndEachSkillAndAnActualNextInventory() {
        fun progress(choose: Int, type: Int) = StudyProgress(
            mapOf("Choose" to ProgressCount(choose, 15), "Type" to ProgressCount(type, 5)),
        )
        assertTrue(offerNextLevel(progress(14, 3), target, 0, now))
        assertFalse(offerNextLevel(progress(13, 3), target, 0, now))
        assertFalse(offerNextLevel(progress(15, 2), target, 0, now))
        assertFalse(offerNextLevel(progress(15, 5), StudyProgress(emptyMap()), 0, now))
        assertFalse(offerNextLevel(null, target, 0, now))
    }

    @Test
    fun dismissalExpiresAtSevenDays() {
        val progress = StudyProgress(mapOf("skill" to ProgressCount(17, 20)))
        val dismissed = now.toEpochMilli()
        assertFalse(offerNextLevel(progress, target, dismissed, now.plusSeconds(7 * 86_400 - 1)))
        assertTrue(offerNextLevel(progress, target, dismissed, now.plusSeconds(7 * 86_400)))
    }
}
