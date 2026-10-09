package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.reading.KanjiReadingQuiz
import io.github.stopcran.kanji.core.reading.KanjiReadingQueue
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingKey
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.random.Random

class KanjiReadingsTest {
    private val now = Instant.parse("2026-01-10T10:00:00Z")

    private fun card(k: String, on: List<String>, kun: List<String>) = ReadingCard(k, on, kun)

    private val cards = listOf(
        card("気", listOf("キ", "ケ"), listOf("き")),
        card("生", listOf("セイ", "ショウ"), listOf("い.きる", "う.まれる", "なま")),
        card("日", listOf("ニチ", "ジツ"), listOf("ひ", "か")),
        card("月", listOf("ゲツ", "ガツ"), listOf("つき")),
        card("火", listOf("カ"), listOf("ひ")),
        card("水", listOf("スイ"), listOf("みず")),
        card("木", listOf("ボク", "モク"), listOf("き", "こ-")),
    )

    @Test
    fun keysIgnoreScriptAndMarkers() {
        assertEquals("ひとつ", ReadingKey.key("ひと.つ"))
        assertEquals("いち", ReadingKey.key("イチ"))
        assertEquals("ひと", ReadingKey.key("ひと-"))
        assertEquals("イチ", ReadingKey.display("いち", ReadingKind.On))
        assertEquals("ひとつ", ReadingKey.display("ひと.つ", ReadingKind.Kun))
        assertEquals("ひと-", ReadingKey.display("ひと-", ReadingKind.Kun))
    }

    @Test
    fun readingSharedBetweenKindsIsNeverAWrongOption() {
        // 気 has キ and き: asking its on'yomi must not offer き (木's kun'yomi) as a distractor, and vice versa.
        val target = cards.first { it.kanji == "気" }
        repeat(200) { seed ->
            for (kind in ReadingKind.entries) {
                val q = KanjiReadingQuiz.question(target, kind, cards, Random(seed)) ?: continue
                val wrong = q.options.filter { it.key != q.correctKey }.map { it.key }
                assertFalse(wrong.toString(), wrong.any { it == "き" || it == "け" || it == "ぎ" })
                assertEquals(q.options.size, q.options.map { it.key }.distinct().size)
            }
        }
    }

    @Test
    fun voicedVariantsOfTargetReadingsAreExcluded() {
        val target = card("火", listOf("カ"), listOf("ひ"))
        val others = listOf(card("日", listOf("ニチ"), listOf("び", "ぴ", "ひ")), card("月", listOf("ゲツ"), listOf("つき")), card("水", listOf("スイ"), listOf("みず")), card("木", listOf("ボク"), listOf("こ")))
        repeat(100) { seed ->
            val q = KanjiReadingQuiz.question(target, ReadingKind.Kun, others + target, Random(seed))!!
            val keys = q.options.map { it.key }
            assertTrue(keys.toString(), "び" !in keys && "ぴ" !in keys)
        }
    }

    @Test
    fun tooFewOptionsGivesNoQuestion() {
        val lonely = card("一", listOf("イチ"), listOf("ひと.つ"))
        assertNull(KanjiReadingQuiz.question(lonely, ReadingKind.On, listOf(lonely), Random(1)))
        assertNull(KanjiReadingQuiz.question(card("々", emptyList(), emptyList()), ReadingKind.On, cards, Random(1)))
        val two = KanjiReadingQuiz.question(lonely, ReadingKind.On, listOf(lonely, card("二", listOf("ニ"), listOf("ふた"))), Random(1))
        assertEquals(2, two!!.options.size)
    }

    @Test
    fun queueOffersOnlyUnlockedOrStartedAndSharesBudget() {
        val noStates = emptyMap<ReadingKind, Map<String, SrsState>>()
        assertTrue(KanjiReadingQueue.build(cards, noStates, emptySet(), emptySet(), 10, now, noise = 0.0).isEmpty())

        val unlocked = setOf("生", "日", "月")
        val q = KanjiReadingQueue.build(cards, noStates, unlocked, emptySet(), 2, now, noise = 0.0)
        // Two new kanji cost two units of budget, whichever kinds they bring.
        assertEquals(2, q.map { it.kanji }.distinct().size)
        assertTrue(q.all { it.isNew })
        assertEquals(4, q.size)

        // A kanji introduced today brings its other kind for free even with no budget left.
        val free = KanjiReadingQueue.build(cards, noStates, unlocked, setOf("生"), 1, now, noise = 0.0)
        assertEquals(setOf("生"), free.map { it.kanji }.toSet())
        assertEquals(2, free.size)
    }

    @Test
    fun dueItemsComeWithoutBudgetAndKindsAreIndependent() {
        val due = SrsState(phase = CardPhase.Review, stability = 3.0, difficulty = 5.0, due = now.minusSeconds(3600), lastReview = now.minusSeconds(86400 * 3), reps = 3)
        val states = mapOf(ReadingKind.On to mapOf("生" to due), ReadingKind.Kun to emptyMap())
        val q = KanjiReadingQueue.build(cards, states, emptySet(), emptySet(), 0, now, noise = 0.0)
        assertEquals(listOf("生" to ReadingKind.On), q.map { it.kanji to it.kind })
        assertFalse(q.single().isNew)
    }

    @Test
    fun sameKanjiIsNotAskedBackToBackWhenAvoidable() {
        val q = KanjiReadingQueue.build(cards, emptyMap(), setOf("生", "日", "月"), emptySet(), 3, now, noise = 0.0)
        assertEquals(6, q.size)
        assertTrue(q.zipWithNext().none { (a, b) -> a.kanji == b.kanji })
    }

    @Test
    fun modeStatePicksOneState() {
        assertEquals(ModeState.Unavailable("x"), ModeState.of(false, "x", 5, 5, true, "l"))
        assertEquals(ModeState.Start(2, 3), ModeState.of(true, "x", 2, 3, true, "l"))
        assertEquals(ModeState.PracticeMore, ModeState.of(true, "x", 0, 0, true, "l"))
        assertEquals(ModeState.Locked("l"), ModeState.of(true, "x", 0, 0, false, "l"))
        assertTrue(ModeState.Start(1, 0).enabled && ModeState.PracticeMore.enabled && !ModeState.Locked("l").enabled)
    }

    @Test
    fun everyKanjiInTheContentSnapshotHasOneValidOption() {
        val dir = File("../../learning-japanese/kanji")
        assumeTrue(dir.exists())
        fun list(text: String, key: String) = Regex("^$key:\\s*\\[(.*)]\\s*$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val all = dir.listFiles { f -> f.extension == "md" }!!.sorted().map { f ->
            val t = f.readText()
            ReadingCard(f.nameWithoutExtension, list(t, "onyomi"), list(t, "kunyomi"), distractors = list(t, "distractors"))
        }
        assertTrue(all.size > 100)
        var skipped = 0
        for (c in all) for (kind in ReadingKind.entries) {
            if (c.ask(kind).isEmpty()) continue
            repeat(3) { seed ->
                val q = KanjiReadingQuiz.question(c, kind, all, Random(seed))
                if (q == null) { skipped++; return@repeat }
                assertTrue("${c.kanji} $kind", q.options.size >= 2)
                val excluded = KanjiReadingQuiz.excludedKeys(c)
                val own = (c.on + c.kun).map { ReadingKey.key(it) }.toSet()
                assertTrue("${c.kanji} $kind correct", q.correctKey in own && q.correctKey in c.ask(kind).map { ReadingKey.key(it) })
                val wrong = q.options.filter { it.key != q.correctKey }
                assertTrue("${c.kanji} $kind wrong option valid: ${wrong.map { it.key }}", wrong.none { it.key in excluded })
                assertEquals(q.options.size, q.options.map { it.key }.distinct().size)
            }
        }
        println("skipped questions: $skipped")
        assertNotNull(all.firstOrNull())
    }
}
