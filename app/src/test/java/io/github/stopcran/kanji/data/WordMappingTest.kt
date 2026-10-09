package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.WordArticle
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.Advancement
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordQuizBuilder
import io.github.stopcran.kanji.core.words.WordStacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class WordMappingTest {
    private val source = "owner/repo"
    private val levels = mapOf("easy" to 5, "medium" to 4, "hard" to 2, "unknown" to null)
    private val now = Instant.parse("2026-01-10T00:00:00Z")
    private val solid = SrsState(CardPhase.Review, 30.0, 5.0, now, now.minusSeconds(86400), 3)

    private fun word(id: String, kanji: List<String> = emptyList(), jlpt: Int? = null) =
        WordEntity(source, id, id, "meaning $id", null, kanji.joinSep(), "", "body", jlpt)

    private val words = listOf(
        word("explicit-kana", jlpt = 5),
        word("explicit-hard", listOf("hard"), 5),
        word("explicit-unknown", listOf("unknown"), 5),
        word("explicit-missing", listOf("missing"), 5),
        word("explicit-n4", listOf("easy"), 4),
        word("explicit-n3", listOf("easy"), 3),
        word("legacy-n5", listOf("easy")),
        word("legacy-n4", listOf("easy", "medium")),
        word("legacy-kana"),
        word("legacy-unknown", listOf("unknown")),
        word("legacy-missing", listOf("missing")),
    )
    private val n5Ids = setOf("explicit-kana", "explicit-hard", "explicit-unknown", "explicit-missing", "legacy-n5")

    @Test
    fun parsedLevelSurvivesSyncAndQuizMappingWithoutChangingIdentity() {
        for (level in listOf(null, 1, 4, 5)) {
            val field = level?.let { "jlpt: $it" } ?: ""
            val article = ContentParser.parseWord(
                "words/same-id.md",
                "---\nword: same-id\nreading: reading\ntitle: meaning\ntype: noun\nkanji: [hard]\ntags: [tag]\n$field\n---\nbody",
            )
            val entity = article.toEntity(source)
            assertEquals(WordEntity(source, "same-id", "reading", "meaning", "noun", "hard", "tag", "body", level), entity)
            assertEquals(WordCard("same-id", "reading", "meaning", "noun", listOf("hard"), listOf("tag"), level), entity.toCard())
        }
    }

    @Test
    fun legacyConstructorsKeepNullLevels() {
        val article = WordArticle("id", "reading", "meaning", null, emptyList(), emptyList(), "body")
        assertNull(article.jlpt)
        assertNull(article.toEntity(source).jlpt)
        assertNull(article.toEntity(source).toCard().jlpt)
        assertNull(WordEntity(source, "id", "reading", "meaning", null, "", "", "body").jlpt)
        assertNull(WordCard("id", "reading", "meaning", null, emptyList()).jlpt)
        assertTrue(article.quizExclusions.isEmpty())
        assertEquals("", article.toEntity(source).quizExclusions)
        assertTrue(article.toEntity(source).toCard().quizExclusions.isEmpty())
    }

    @Test
    fun exclusionsSurviveParserSyncStorageAndQuizMappingWithoutChangingLevelsOrIdentity() {
        val article = ContentParser.parseWord(
            "words/a.md",
            "---\nword: a\nreading: reading\ntitle: meaning a\nkanji: []\njlpt: 5\nquiz_exclusions: [b, c]\n---\nbody",
        )
        val entity = article.toEntity(source)
        assertEquals("a", entity.word)
        assertEquals(source, entity.sourceId)
        assertEquals(5, entity.jlpt)
        assertEquals(listOf("b", "c").joinSep(), entity.quizExclusions)
        assertEquals(listOf("b", "c"), entity.toCard().quizExclusions)
        val entities = listOf(entity, word("b", jlpt = 5), word("c", jlpt = 5), word("d", jlpt = 5))
        assertEquals(entities, entities.inWordStack(WordStacks.n5, emptyMap()))
        for (direction in listOf(WordDirection.JpToEn, WordDirection.EnToJp)) {
            assertEquals(
                setOf("a", "d"),
                WordQuizBuilder.options(entity.toCard(), entities.map { it.toCard() }, direction, Random(1)).map { it.word }.toSet(),
            )
            assertFalse(
                WordQuizBuilder.options(entities[1].toCard(), entities.map { it.toCard() }, direction, Random(1)).any { it.word == "a" },
            )
        }
    }

    @Test
    fun exclusionMetadataDoesNotChangeStackMembershipOrSchedulingQueues() {
        val labelled = words.mapIndexed { index, word ->
            word.copy(quizExclusions = listOf(words[(index + 1) % words.size].word).joinSep())
        }
        val states = words.associate { it.word to solid }
        for (stack in listOf(WordStacks.n5, WordStacks.n4, WordStacks.all)) {
            val before = words.inWordStack(stack, levels).map { it.word }
            val after = labelled.inWordStack(stack, levels).map { it.word }
            assertEquals(before, after)
            for (direction in WordDirection.entries) {
                assertEquals(
                    WordQueues.build(direction, before, states, states, now, 0, noise = 0.0),
                    WordQueues.build(direction, after, states, states, now, 0, noise = 0.0),
                )
            }
        }
    }

    @Test
    fun persistedWordLevelsDriveCumulativeStacksWithLegacyFallback() {
        assertEquals(n5Ids, words.inWordStack(WordStacks.n5, levels).map { it.word }.toSet())
        assertEquals(n5Ids + setOf("explicit-n4", "legacy-n4"), words.inWordStack(WordStacks.n4, levels).map { it.word }.toSet())
        assertEquals(words, words.inWordStack(WordStacks.all, levels))
        assertEquals(
            n5Ids - "legacy-n5",
            words.inWordStack(WordStacks.n5, emptyMap()).map { it.word }.toSet(),
        )
    }

    @Test
    fun filteredQueuesRetainStateBudgetAndDirectionGates() {
        val ids = words.inWordStack(WordStacks.n5, levels).map { it.word }
        val forward = mapOf(
            "explicit-kana" to solid,
            "explicit-hard" to solid.copy(reps = 1),
            "legacy-n5" to solid,
            "explicit-n4" to solid,
        )
        val due = WordQueues.build(WordDirection.JpToEn, ids, forward, emptyMap(), now, newBudget = 0, noise = 0.0)
        assertEquals(setOf("explicit-kana", "explicit-hard", "legacy-n5"), due.map { it.kanji }.toSet())
        assertTrue(due.none { it.isNew })
        due.forEach { assertEquals(forward[it.kanji], it.state) }

        val withNew = WordQueues.build(WordDirection.JpToEn, ids, forward, emptyMap(), now, newBudget = 10, noise = 0.0)
        assertEquals(n5Ids, withNew.map { it.kanji }.toSet())
        assertEquals(2, withNew.count { it.isNew })
        val extra = WordQueues.build(WordDirection.JpToEn, ids, forward, emptyMap(), now, newBudget = 0, extra = true, noise = 0.0)
        assertEquals(n5Ids, extra.map { it.kanji }.toSet())

        val reverse = WordQueues.build(
            WordDirection.EnToJp, ids, mapOf("explicit-unknown" to solid), forward, now, newBudget = 10, noise = 0.0,
        )
        assertEquals(setOf("explicit-kana", "explicit-unknown", "legacy-n5"), reverse.map { it.kanji }.toSet())
    }

    @Test
    fun readingQueuesPreserveExplicitLevelsMetadataAndWrittenWordIdentity() {
        val explicit = word("explicit-hard", listOf("hard"), 5).copy(reading = "reading-hard", quizExclusions = "paired")
        val kana = word("explicit-kana", jlpt = 5)
        val n4 = word("explicit-n4", listOf("easy"), 4).copy(reading = "reading-n4")
        val legacy = word("legacy-n5", listOf("easy")).copy(reading = "reading-legacy")
        val all = listOf(explicit, kana, n4, legacy)
        val inStack = all.inWordStack(WordStacks.n5, levels)
        val readable = inStack.forDirection(WordDirection.Reading)
        assertEquals(listOf(explicit, kana, legacy), inStack.forDirection(WordDirection.JpToEn))
        assertEquals(listOf(explicit, legacy), readable)
        assertEquals(5, readable.first().toCard().jlpt)
        assertEquals(listOf("paired"), readable.first().toCard().quizExclusions)
        val forward = all.associate { it.word to solid }
        val queue = WordQueues.build(
            WordDirection.Reading, readable.map { it.word }, emptyMap(), forward, now, newBudget = 10, noise = 0.0,
        )
        assertEquals(setOf(explicit.word, legacy.word), queue.map { it.kanji }.toSet())
        val state = solid.toEntity(source, WordStacks.n5.id, explicit.word, WordDirection.Reading.mode)
        assertEquals(explicit.word, state.kanji)
        assertEquals("WordReading", state.mode)
        assertEquals(solid, state.toSrs())
    }

    @Test
    fun explicitN5WordsCountTowardsAdvancementButExplicitN4WordsDoNot() {
        val legacy = (1..10).map { word("legacy-$it", listOf("easy")) }
        val added = (1..10).map { word("added-$it", jlpt = 5) }
        val n4 = (1..10).map { word("n4-$it", listOf("easy"), jlpt = 4) }
        val known = legacy.associate { it.word to solid }
        fun ready(words: List<WordEntity>, states: Map<String, SrsState>): Boolean {
            val n5 = words.inWordStack(WordStacks.n5, levels)
            return Advancement.ready(List(2) { n5.map { states[it.word] } }, now)
        }

        assertTrue(ready(legacy + n4, known))
        assertFalse(ready(legacy + added + n4, known))
        assertTrue(ready(legacy + added + n4, known + added.associate { it.word to solid }))
    }
}
