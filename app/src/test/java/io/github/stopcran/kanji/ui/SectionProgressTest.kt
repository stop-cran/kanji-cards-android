package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.approvedListening
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.content.CounterReverse
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.kana.KanaCatalog
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.listening.ListeningScope
import io.github.stopcran.kanji.core.listening.TaskUsage
import io.github.stopcran.kanji.core.approvedTask
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.data.ListeningEntity
import io.github.stopcran.kanji.data.ListeningTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SectionProgressTest {
    private val now = Instant.parse("2026-11-15T12:00:00Z")

    @Test
    fun countersUseExactLevelGroupAndModeInventories() {
        val index = CounterIndex(
            listOf(
                counterFile("five", 5, "everyday"),
                counterFile("four", 4, "time"),
            ),
        )
        val states = CounterMode.entries.associateWith { mode ->
            mapOf("five-card" to SrsState(CardPhase.Review), "four-card" to SrsState(CardPhase.Review))
        }

        val n4Time = counterProgress(index, states, CounterScope.N4, CounterGroup.Time, now)
        assertEquals(1, n4Time.skills.getValue(CounterMode.Choose.name).total)
        assertEquals(1, n4Time.skills.getValue(CounterMode.Type.name).total)
        assertEquals(1, n4Time.skills.getValue(CounterMode.Noun.name).total)
        assertEquals(0, counterProgress(index, states, CounterScope.N5, CounterGroup.Time, now).total.total)
        assertEquals(8, counterProgress(index, states, CounterScope.All, CounterGroup.All, now).total.total)
    }

    @Test
    fun grammarKeepsAuthoredExactLevelsAndUnclassifiedSeparate() {
        val fixture = grammarFixture(examples = 2)
        val exercises = listOf(
            fixture.exercises[0].copy(id = "n4", jlpt = 4),
            fixture.exercises[1].copy(id = "n1", jlpt = 1),
            fixture.exercises[2].copy(id = "unknown", jlpt = null),
        )
        val progress = grammarProgress(
            exercises,
            mapOf("n4" to SrsState(CardPhase.Review), "n1" to SrsState(CardPhase.Review)),
            now,
        )

        assertEquals(1, progress.getValue(GrammarScope.N4).total.total)
        assertEquals(1, progress.getValue(GrammarScope.N1).total.total)
        assertEquals(1, progress.getValue(GrammarScope.Unclassified).total.total)
        assertEquals(3, progress.getValue(GrammarScope.All).total.total)
        assertEquals(0, progress.getValue(GrammarScope.N3).total.total)
        assertEquals(
            0,
            grammarProgress(exercises.filter { it.jlpt != null }, emptyMap(), now)
                .getValue(GrammarScope.Unclassified).total.total,
        )
    }

    @Test
    fun kanaProgressSeparatesScriptAndModeInventories() {
        val script = KanaScript.Hiragana
        val mode = KanaMode.Draw
        val states = mapOf(
            KanaStateKey(script, mode) to listOf(
                io.github.stopcran.kanji.data.ReviewStateEntity(
                    "builtin-kana", script.id, KanaCatalog.all(script).first().kana, mode.name, "Review", 10.0, 5.0,
                    now.toEpochMilli(), now.minusSeconds(86_400).toEpochMilli(), 1, 0,
                ),
            ),
        )
        val overview = KanaOverview(states, null, now)

        assertEquals(1, overview.progress(script, mode).total.solid)
        assertEquals(KanaCatalog.all(script).size, overview.progress(script, mode).total.total)
        assertEquals(0, overview.progress(KanaScript.Katakana, mode).total.solid)
        assertEquals(KanaCatalog.all(KanaScript.Katakana).size, overview.progress(KanaScript.Katakana, mode).total.total)
        assertEquals(KanaMode.entries.size, overview.progress(script).skills.size)
    }

    @Test
    fun listeningProgressSeparatesPlayablePassagesAndScoredTasks() {
        val passage = approvedListening("progress")
        val task = approvedTask()
        val passageRow = ListeningEntity(
            "owner/repo", passage.passage.id, passage.json, passage.contentHash,
            passage.passage.audio!!.sha256, 20_000, true, null, 0,
        )
        val taskRow = ListeningTaskEntity(
            "owner/repo", task.task.id, task.json, task.contentHash, task.task.exposureId,
            task.task.taskType.wire, task.task.title, TaskUsage.Scored.name, 20_000, true, null, 0,
        )
        val progress = listeningProgress(
            listOf(passageRow, passageRow.copy(id = "blocked", ready = false)),
            listOf(taskRow, taskRow.copy(id = "example", usage = TaskUsage.Example.name),
                taskRow.copy(id = "placeholder", json = null, taskType = null, ready = false)),
            mapOf(
                passage.passage.id to SrsState(CardPhase.Review),
                task.task.id to SrsState(CardPhase.Review),
                "partial-open" to SrsState(CardPhase.Review),
            ),
            now,
        )

        val n5 = progress.getValue(ListeningScope.N5)
        assertEquals(1, n5.skills.getValue("passages").total)
        assertEquals(1, n5.skills.getValue("tasks").total)
        assertEquals(0, progress.getValue(ListeningScope.N4).total.total)
    }

    private fun counterFile(id: String, level: Int, group: String) = CounterFile(
        schemaVersion = 2,
        id = id,
        counter = "本",
        reading = "ほん",
        title = id,
        level = level,
        group = group,
        cards = listOf(
            CounterCard(
                id = "$id-card", text = "一{}あります。", noun = "本", phrase = "一本",
                reading = "いっぽん", good = listOf("本"), distractors = listOf("枚", "個", "冊"),
            ),
        ),
        reverse = listOf(CounterReverse("$id-reverse", "book", listOf("book", "cup", "pen", "cat"))),
    )
}
