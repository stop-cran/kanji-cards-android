package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.core.srs.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuizSessionTest {
    private fun session(vararg items: String, confirm: Boolean = true) = QuizSession(items.toList(), { it }, confirm)

    @Test
    fun gradesFollowTheAnswer() {
        val s = session("a", "b", "c", "d")
        assertEquals(Grade.Good, s.answer(s.take()!!, ok = true).grade)
        assertEquals(Grade.Hard, s.answer(s.take()!!, ok = true, guessed = true).grade)
        assertEquals(Grade.Hard, s.answer(s.take()!!, ok = true, weak = true).grade)
        assertEquals(Grade.Again, s.answer(s.take()!!, ok = false).grade)
        assertEquals(4, s.answered)
        assertEquals(3, s.correct)
    }

    @Test
    fun failedCardComesBackThenIsConfirmedWithoutRecording() {
        val s = session("a", "b", "c", "d", "e", "f", "g", "h", "i")
        val a = s.take()!!
        assertEquals(true, s.answer(a, ok = false).record)
        repeat(3) { s.answer(s.take()!!, ok = true) }
        assertEquals("a", s.take())
        assertEquals(true, s.answer("a", ok = true).record)
        repeat(5) { s.answer(s.take()!!, ok = true) }
        assertEquals("a", s.take())
        assertEquals(false, s.answer("a", ok = true).record)
        assertNull(s.take())
    }

    @Test
    fun scoreCanDifferFromRecall() {
        val s = session("a", confirm = false)
        s.answer(s.take()!!, ok = true, weak = true, countsAsCorrect = false)
        assertEquals(1, s.answered)
        assertEquals(0, s.correct)
    }
}
