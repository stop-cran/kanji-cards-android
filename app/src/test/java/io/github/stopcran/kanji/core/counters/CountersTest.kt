package io.github.stopcran.kanji.core.counters

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.content.CounterHard
import io.github.stopcran.kanji.core.content.CounterReverse
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class CountersTest {
    private val pen = CounterCard(
        id = "hon-pen-1", text = "机の上にペンが三{}あります。", en = "Three pens.", noun = "ペン", phrase = "三本", reading = "さんぼん",
        good = listOf("本", "三本"), kana = listOf("さんぼん"),
        hard = listOf(CounterHard(listOf("つ", "三つ", "みっつ"), "三つ", "みっつ", "General counting; 本 is the usual counter.")),
        distractors = listOf("匹", "冊", "回"),
    )
    private val cat = pen.copy(
        id = "hiki-cat-1", noun = "猫", phrase = "二匹", reading = "にひき", good = listOf("匹", "二匹"), kana = listOf("にひき"),
        hard = emptyList(), distractors = listOf("本", "冊", "回"),
    )
    private val bag = pen.copy(id = "hon-bag-1")
    private val book = pen.copy(id = "hon-book-1")
    private val hon = CounterFile(
        1, "hon", "本", "ほん", "long things", cards = listOf(pen, bag, book),
        reverse = listOf(CounterReverse("hon-noun-1", "hon-pen-1", listOf("hon-pen-1", "hiki-cat-1", "hon-bag-1", "hon-book-1"))),
    )
    private val hiki = CounterFile(1, "hiki", "匹", "ひき", "small animals", cards = listOf(cat))
    private val index = CounterIndex(listOf(hon, hiki))

    @Test
    fun filledSentenceDoesNotRepeatTheNumber() {
        assertEquals("机の上にペンが三本あります。", index.sentence(pen))
        assertEquals("机の上にペンが三本あります。", index.sentence(pen.copy(text = "机の上にペンが{}あります。")))
        assertEquals("何本あります。", index.sentence(pen.copy(text = "何{}あります。", phrase = "何本")))
    }

    private fun solid() = SrsState(CardPhase.Review, stability = 3.0, difficulty = 5.0, due = Instant.EPOCH, lastReview = Instant.EPOCH, reps = 3, lapses = 0)

    @Test
    fun numeralsAreNormalizedWithoutLosingIdentity() {
        assertEquals(CounterText.normalize("三本"), CounterText.normalize("3本"))
        assertEquals(CounterText.normalize("三本"), CounterText.normalize("３本"))
        assertNotEquals(CounterText.normalize("二本"), CounterText.normalize("三本"))
        assertEquals("20本", CounterText.normalize("二十本"))
        assertEquals("11本", CounterText.normalize("十一本"))
        assertEquals("100本", CounterText.normalize("百本"))
        assertEquals(CounterText.normalize("五、六本"), CounterText.normalize("五六本"))
        assertNotEquals(CounterText.normalize("五六本"), CounterText.normalize("56本"))
    }

    @Test
    fun gradingTiers() {
        assertEquals(CounterVerdict.Good, CounterGrader.grade("三本", pen, "ほん").verdict)
        assertEquals(CounterVerdict.Good, CounterGrader.grade(" 3本 ", pen, "ほん").verdict)
        assertEquals(CounterVerdict.Good, CounterGrader.grade("本", pen, "ほん").verdict)
        assertEquals(CounterVerdict.Wrong, CounterGrader.grade("二本", pen, "ほん").verdict)
        assertEquals(CounterVerdict.Yellow, CounterGrader.grade("さんぼん", pen, "ほん").verdict)
        assertEquals(CounterVerdict.Yellow, CounterGrader.grade("ほん", pen, "ほん").verdict)
        val hard = CounterGrader.grade("三つ", pen, "ほん")
        assertEquals(CounterVerdict.Yellow, hard.verdict)
        assertEquals("General counting; 本 is the usual counter.", hard.reason)
        assertTrue(hard.ok)
        assertEquals(CounterVerdict.Wrong, CounterGrader.grade("三匹", pen, "ほん").verdict)
        assertEquals(CounterVerdict.Wrong, CounterGrader.grade("", pen, "ほん").verdict)
        assertFalse(CounterGrader.grade("匹", pen, "ほん").ok)
    }

    @Test
    fun chooseOptionsHaveOneCorrectAndAreShuffled() {
        val orders = (0 until 20).map { CounterOptions.choose(pen, "本", Random(it)) }
        assertTrue(orders.all { it.size == 4 && it.count { o -> o == "本" } == 1 && it.toSet().size == 4 })
        assertTrue(orders.map { it.indexOf("本") }.toSet().size > 1)
    }

    @Test
    fun nounOptionsKeepTheAnswer() {
        val opts = CounterOptions.nouns(hon.reverse.single(), Random(1))
        assertEquals(hon.reverse.single().options.toSet(), opts.toSet())
        assertEquals("hon", index.reverseFile("hon-noun-1")?.id)
    }

    @Test
    fun typeOpensAfterSolidChooseOrOnceStarted() {
        val none = emptyMap<String, SrsState>()
        assertTrue(CounterQueues.eligible(CounterMode.Type, index, none, none).isEmpty())
        val choose = mapOf("hon-pen-1" to solid())
        assertEquals(listOf("hon-pen-1"), CounterQueues.eligible(CounterMode.Type, index, none, choose))
        val started = mapOf("hiki-cat-1" to solid())
        assertEquals(listOf("hiki-cat-1"), CounterQueues.eligible(CounterMode.Type, index, started, none))
    }

    @Test
    fun nounOpensAfterThreeSolidSentencesOfTheCounter() {
        val none = emptyMap<String, SrsState>()
        val two = mapOf("hon-pen-1" to solid(), "hon-bag-1" to solid())
        assertTrue(CounterQueues.eligible(CounterMode.Noun, index, none, two).isEmpty())
        val three = two + ("hon-book-1" to solid())
        assertEquals(listOf("hon-noun-1"), CounterQueues.eligible(CounterMode.Noun, index, none, three))
    }

    @Test
    fun chooseQueueRespectsNewBudget() {
        val none = emptyMap<String, SrsState>()
        val q = CounterQueues.build(index, CounterQueueRequest(CounterMode.Choose, none, none, Instant.now(), newBudget = 2))
        assertEquals(2, q.size)
        assertTrue(q.all { it.isNew })
        assertEquals(1, CounterQueues.build(index, CounterQueueRequest(CounterMode.Choose, none, none, Instant.now(), 0, extraLimit = 1)).size)
    }

    @Test
    fun counterStatesNeverCollideWithOtherIds() {
        val ids = CounterMode.entries.flatMap { index.ids(it) }
        assertTrue(ids.all { Regex("[a-z]+-[a-z]+-\\d+").matches(it) })
        assertTrue(CounterMode.entries.map { it.mode.name }.toSet().size == 3)
    }

    private fun json(id: String, cards: String, reverse: String = "") =
        """{"schemaVersion":1,"id":"$id","counter":"本","reading":"ほん","title":"t","level":5,"cards":[$cards],"reverse":[$reverse]}"""

    private fun card(id: String, text: String = "ペンが三{}ある。", distractors: String = "\"匹\",\"冊\",\"回\"") =
        """{"id":"$id","kind":"plain","text":"$text","en":"e","noun":"ペン","phrase":"三本","reading":"さんぼん",""" +
            """"good":["本","三本"],"kana":["さんぼん"],"distractors":[$distractors]}"""

    private fun parse(vararg files: Pair<String, String>): io.github.stopcran.kanji.core.content.ParsedContent {
        val bytes = files.associate { (p, t) -> p to t.toByteArray() }
        val entries = bytes.entries.joinToString(",") { (p, b) -> "\"$p\":\"${ContentParser.sha256(b)}\"" }
        val manifest = """{"schemaVersion":1,"contentVersion":"v","files":{$entries}}""".toByteArray()
        return ContentParser.parse(bytes + ("manifest.json" to manifest))
    }

    @Test
    fun parsesValidCounterFile() {
        val p = parse("counters/hon.json" to json("hon", card("hon-a-1")))
        assertEquals(emptyList<String>(), p.problems)
        assertEquals("hon-a-1", p.counters.single().cards.single().id)
    }

    @Test
    fun rejectsMalformedCounterFiles() {
        assertTrue(parse("counters/hon.json" to json("hon", card("hon-a-1", text = "ペンが三あります。"))).counters.isEmpty())
        assertTrue(parse("counters/hon.json" to json("hon", card("hon-a-1", distractors = "\"匹\",\"冊\""))).counters.isEmpty())
        assertTrue(parse("counters/hon.json" to json("hiki", card("hon-a-1"))).counters.isEmpty())
        assertTrue(parse("counters/hon.json" to "{not json").counters.isEmpty())
    }

    @Test
    fun duplicateCardIdAcrossFilesDropsTheLaterFile() {
        val p = parse("counters/hon.json" to json("hon", card("same-a-1")), "counters/zzz.json" to json("zzz", card("same-a-1")))
        assertEquals(listOf("hon"), p.counters.map { it.id })
        assertTrue(p.problems.any { it.contains("duplicate card ID") })
    }

    @Test
    fun reverseWithMissingOptionIsDropped() {
        val reverse = """{"id":"hon-noun-1","answer":"hon-a-1","options":["hon-a-1","hon-b-1","hon-c-1","nope-x-1"]}"""
        val p = parse("counters/hon.json" to json("hon", card("hon-a-1") + "," + card("hon-b-1") + "," + card("hon-c-1"), reverse))
        assertTrue(p.counters.single().reverse.isEmpty())
        assertTrue(p.problems.any { it.contains("missing cards") })
    }
}
