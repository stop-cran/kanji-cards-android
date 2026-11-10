package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterQueueRequest
import io.github.stopcran.kanji.core.counters.CounterQueues
import io.github.stopcran.kanji.core.counters.CounterRoutes
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQueues
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class ModeBatchTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val ids = (1..35).map { "item-$it" }
    private val solid = SrsState(CardPhase.Review, stability = 3.0, reps = 3, due = now.plusSeconds(86400))

    @Test
    fun modeNewIsBoundedButDoesNotSpendTheContentBudgetAgain() {
        for (budget in listOf(0, 1, 5)) for (seed in 0..30) {
            val known = ids.take(25).toSet()
            val queue = QueueBuilder.modeBatch(ids, emptyMap(), known, now, budget, rnd = Random(seed))
            assertEquals(10, queue.size)
            assertTrue(queue.all { it.isNew })
            assertTrue(queue.count { it.kanji !in known } <= budget)
        }
        assertEquals(20, QueueBuilder.modeBatch(ids, emptyMap(), ids.toSet(), now, 20).size)
        assertEquals(1, QueueBuilder.modeBatch(ids, emptyMap(), emptySet(), now, 1).size)
        assertTrue(QueueBuilder.modeBatch(ids, emptyMap(), emptySet(), now, -1).isEmpty())
    }

    @Test
    fun dueReviewsStayUncappedAndUpcomingStatesAreNotReintroduced() {
        val due = ids.take(20).associateWith { solid.copy(due = now.minusSeconds(1)) }
        val upcoming = ids[20] to solid
        val queue = QueueBuilder.modeBatch(ids, due + upcoming, ids.toSet(), now, 0)
        assertEquals(20, queue.count { !it.isNew })
        assertEquals(10, queue.count { it.isNew })
        assertTrue(queue.none { it.kanji == upcoming.first })
        assertEquals(due, queue.filterNot { it.isNew }.associate { it.kanji to it.state })
    }

    @Test
    fun chooseTypeAndWholeExpressionSkillsShareOnlyMatchingContentIds() {
        val cards = ids.map { CounterCard(it, text = "{}", noun = "pen", phrase = "三本", reading = "さんぼん",
            good = listOf("本"), distractors = listOf("匹", "冊", "回")) }
        val expressions = ids.map { CounterExpression(it, "", "一日", listOf("一日"), "いちにち", listOf("いちにち"), "") }
        val index = CounterIndex(listOf(CounterFile(1, "cards", "", "", "", cards = cards)))
        val expressionIndex = CounterIndex(listOf(CounterFile(2, "expressions", "", "", "", expressions = expressions)))
        val choose = ids.associateWith { solid }
        for (budget in listOf(0, 1)) {
            for (mode in listOf(CounterMode.Choose, CounterMode.Type, CounterMode.Reading, CounterMode.Expression)) {
                val pool = if (mode == CounterMode.Expression) expressionIndex else index
                val queue = CounterQueues.build(pool, CounterQueueRequest(mode, emptyMap(), choose, now, budget, introduced = ids.toSet()))
                assertEquals(mode.name, 10, queue.size)
                assertTrue(queue.all { it.isNew })
            }
        }
        assertTrue(CounterQueues.build(index, CounterQueueRequest(CounterMode.Type, emptyMap(), emptyMap(), now, 0,
            introduced = ids.toSet())).isEmpty())
        assertEquals(1, CounterQueues.build(expressionIndex, CounterQueueRequest(CounterMode.Expression, emptyMap(),
            choose, now, 1)).size)
    }

    @Test
    fun allWordDirectionsKeepTheirGatesAndIndependentSchedulesAtZeroBudget() {
        for (direction in WordDirection.entries) {
            val gate = ids.associateWith { solid }
            val queue = WordQueues.build(direction, ids, emptyMap(), gate, now, 0, introduced = ids.toSet())
            assertEquals(direction.name, 10, queue.size)
            assertTrue(queue.all { it.state == SrsState() && it.isNew })
            if (direction.gated) {
                assertTrue(WordQueues.build(direction, ids, emptyMap(), emptyMap(), now, 0, introduced = ids.toSet()).isEmpty())
            }
            assertTrue(WordQueues.build(direction, ids, emptyMap(), gate, now, 0).isEmpty())
            assertEquals(10, WordQueues.build(direction, ids, emptyMap(), gate, now, 0, extra = true).size)
        }
    }

    @Test
    fun continueIsANewOrdinaryRouteWithTheCapturedCounterScope() {
        val route = CounterRoutes.create(CounterMode.Type, CounterGroup.Time, CounterScope.N3, stamp = 1)
        val next = CounterRoutes.nextBatch(route)
        assertNotEquals(route, next)
        assertEquals(CounterScope.N3, CounterScope.fromRoute(next))
        assertEquals(CounterGroup.Time, CounterGroup.fromRoute(next))
        assertEquals(CounterMode.Type, CounterMode.fromCode(next.split(':')[2]))
        assertFalse(next.endsWith(":extra"))
        assertTrue(CounterRoutes.extra(route).endsWith(":extra"))
    }
}
