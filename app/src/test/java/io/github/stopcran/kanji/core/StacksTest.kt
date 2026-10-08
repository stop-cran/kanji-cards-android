package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.Stacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StacksTest {
    private val cards = listOf(listOf("jlpt-n5", "starter"), listOf("jlpt-n4", "starter"), listOf("jlpt-n3"))

    @Test
    fun listsOnlyStacksWithCards() {
        assertEquals(listOf("all", "starter", "jlpt-n5", "jlpt-n4", "jlpt-n3"), Stacks.available(cards).map { it.id })
    }

    @Test
    fun filtersByTag() {
        val starter = Stacks.find("starter", Stacks.available(cards))
        assertTrue(starter.contains(cards[0]))
        assertFalse(starter.contains(cards[2]))
        assertTrue(Stacks.everything.contains(emptyList()))
    }

    @Test
    fun unknownStackFallsBackToAll() {
        assertEquals("all", Stacks.find("jlpt-n1", Stacks.available(cards)).id)
    }
}
