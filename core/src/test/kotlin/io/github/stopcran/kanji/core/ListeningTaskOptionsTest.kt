package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.listening.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ListeningTaskOptionsTest {
    @Test fun freshPermutationsKeepAuthoredNumbersAndGradeByIdAfterRestoration() {
        val task = taskFixture().task
        val orders = (0..12).map { seed -> requireNotNull(ListeningTaskPolicy.shuffledOptions(task, Random(seed))) }
        assertTrue(orders.distinct().size > 1)
        orders.forEach { order ->
            assertEquals(task.options.map { it.id }.toSet(), order.toSet())
            val state = ListeningTaskAttempt(optionOrder = order, answer = task.answer,
                completedStages = task.stages.map { it.id }.toSet())
            val restored = Json.decodeFromString(ListeningTaskAttempt.serializer(),
                Json.encodeToString(ListeningTaskAttempt.serializer(), state))
            assertEquals(order, restored.optionOrder)
            assertEquals(order, ListeningTaskPolicy.options(task, restored.optionOrder).map { it.id })
            assertEquals(task.options.associateBy { it.id },
                ListeningTaskPolicy.options(task, order).associateBy { it.id })
            assertEquals(io.github.stopcran.kanji.core.srs.Grade.Good,
                ListeningTaskPolicy.grade(task, restored, exposed = true, giveUp = false))
        }
    }

    @Test fun oldOrMalformedOrdersKeepTheCompleteAuthoredOptions() {
        val task = taskFixture().task
        val ids = task.options.map { it.id }
        val old = Json.decodeFromString(ListeningTaskAttempt.serializer(), """{"phase":"answer"}""")
        listOf(old.optionOrder, emptyList(), ids.dropLast(1), ids + ids.first(),
            List(ids.size) { ids.first() }, ids.dropLast(1) + "missing").forEach { order ->
            assertEquals(task.options, ListeningTaskPolicy.options(task, order))
        }
    }

    @Test fun audioVisualAndPictureBoundTextNeverUseAStoredPermutation() {
        val text = taskFixture().task
        val excluded = listOf(
            text.copy(visual = TaskVisual("context", "Context picture")),
            text.copy(visual = TaskVisual("required", "Numbered arrangements")),
            text.copy(choiceMode = TaskChoiceMode.Visual),
            taskFixture("borrow-pencil").task,
            taskFixture("park-invitation").task,
        )
        excluded.forEach { task ->
            assertNull(ListeningTaskPolicy.shuffledOptions(task, Random(0)))
            assertEquals(task.options, ListeningTaskPolicy.options(task, task.options.map { it.id }.reversed()))
        }
    }
}
