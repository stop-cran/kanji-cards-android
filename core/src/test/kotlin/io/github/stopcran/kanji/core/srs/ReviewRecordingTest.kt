package io.github.stopcran.kanji.core.srs

import io.github.stopcran.kanji.core.words.WordDirection
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewRecordingTest {
    @Test
    fun modePolicyKeepsChoicesCappedAndTypingUncappedWithTheCapturedOwner() {
        val at = Instant.parse("2026-10-30T23:59:59Z")
        val recording = ReviewRecording(Clock.fixed(at, ZoneOffset.UTC))
        for (mode in listOf(StudyMode.Quiz) + WordDirection.entries.map { it.mode }) {
            val states = mutableMapOf<String, SrsState>()
            val cap = if (mode == StudyMode.WordType) null else Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS
            val identity = ReviewIdentity("first/repo", "borrowed-owner", "item", mode, "session")
            val record = recording.confirm(identity, states, Grade.Good, cap)
            if (cap == null) assertTrue(record.state.stability > Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS)
            else assertTrue(record.state.stability <= cap)
            assertEquals(at, record.at)
            assertEquals(at, record.state.lastReview)
            assertEquals(record.state, states["item"])
            assertEquals("first/repo", record.sourceId)
            assertEquals("borrowed-owner", record.stack)
            assertEquals(mode, record.mode)
            assertEquals("session", record.sessionId)
        }
    }
}
