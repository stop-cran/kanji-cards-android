package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.words.Advancement
import io.github.stopcran.kanji.core.words.ReadingOptions
import io.github.stopcran.kanji.core.words.WordBlend
import io.github.stopcran.kanji.core.words.WordCard
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordGate
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordQuizBuilder
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.core.words.hasReadingToLearn
import io.github.stopcran.kanji.core.words.wordLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class WordsTest {
    private val now = Instant.parse("2026-01-10T00:00:00Z")
    private val meaningDirections = listOf(WordDirection.JpToEn, WordDirection.EnToJp)
    private fun review(stability: Double, reps: Int = 3, dueOffsetSec: Long = 0, lastDaysAgo: Long = 1) =
        SrsState(CardPhase.Review, stability, 5.0, now.plusSeconds(dueOffsetSec), now.minusSeconds(lastDaysAgo * 86400), reps)

    @Test
    fun levelIsTheHardestKanjiAndNullWhenUnknown() {
        val levels = mapOf("開" to 4, "音" to 5, "京" to null)
        assertEquals(4, wordLevel(listOf("開", "音"), levels))
        assertEquals(5, wordLevel(listOf("音"), levels))
        assertNull(wordLevel(emptyList(), levels))
        assertNull(wordLevel(listOf("音", "京"), levels))
        assertNull(wordLevel(listOf("音", "?"), levels))
    }

    @Test
    fun explicitLevelOverridesKanjiInference() {
        val levels = mapOf("音" to 5, "開" to 4, "難" to 1, "京" to null)
        assertEquals(5, wordLevel(listOf("音", "難"), levels, jlpt = 5))
        assertEquals(5, wordLevel(listOf("京"), levels, jlpt = 5))
        assertEquals(5, wordLevel(listOf("missing"), levels, jlpt = 5))
        assertEquals(4, wordLevel(listOf("音"), levels, jlpt = 4))
        for (level in 1..5) {
            assertEquals(level, wordLevel(emptyList(), emptyMap(), jlpt = level))
        }
        assertEquals(4, wordLevel(listOf("開", "音"), levels, jlpt = null))
    }

    @Test
    fun stacksAreCumulative() {
        assertTrue(WordStacks.n5.contains(5))
        assertFalse(WordStacks.n5.contains(4))
        assertTrue(WordStacks.n4.contains(5) && WordStacks.n4.contains(4))
        assertFalse(WordStacks.n4.contains(3))
        assertFalse(WordStacks.n4.contains(null))
        assertTrue(WordStacks.all.contains(null))
        assertEquals(listOf(WordStacks.n5), WordStacks.offered(false))
        assertEquals(WordStacks.n5, WordStacks.find("words-n4", WordStacks.offered(false)))
    }

    @Test
    fun reverseDirectionNeedsTwoForwardAnswers() {
        assertFalse(WordGate.reverseUnlocked(null))
        assertFalse(WordGate.reverseUnlocked(review(2.0, reps = 1)))
        assertTrue(WordGate.reverseUnlocked(review(2.0, reps = 2)))
        val words = listOf("a", "b", "c")
        val jp = mapOf("a" to review(2.0, reps = 2, dueOffsetSec = 99999), "b" to review(2.0, reps = 1))
        val en = mapOf("c" to review(1.0, reps = 1, dueOffsetSec = -10))
        val q = WordQueues.build(WordDirection.EnToJp, words, en, jp, now, newBudget = 10, noise = 0.0)
        // "a" is unlocked (new here), "c" was already started, "b" is still locked
        assertEquals(setOf("a", "c"), q.map { it.kanji }.toSet())
    }

    @Test
    fun otherDirectionOnlyDefersOrderingSlightly() {
        assertEquals(1.0, WordBlend.urgencyFactor(null, now), 0.0)
        assertEquals(1.0, WordBlend.urgencyFactor(SrsState(), now), 0.0)
        val strong = WordBlend.urgencyFactor(review(100.0, lastDaysAgo = 1), now)
        assertTrue(strong < 1.0 && strong >= 1.0 - WordBlend.MAX_DEFERRAL)
        val forgotten = WordBlend.urgencyFactor(review(0.5, lastDaysAgo = 400), now)
        assertTrue(forgotten > strong)

        val own = mapOf("x" to review(5.0, dueOffsetSec = -86400, lastDaysAgo = 6), "y" to review(5.0, dueOffsetSec = -86400, lastDaysAgo = 6))
        val other = mapOf("x" to review(200.0, lastDaysAgo = 1))
        val q = WordQueues.build(WordDirection.JpToEn, listOf("x", "y"), own, other, now, 0, noise = 0.0)
        assertEquals(listOf("y", "x"), q.map { it.kanji })
        assertEquals(own["x"], q.first { it.kanji == "x" }.state)
    }

    @Test
    fun optionsHaveOneCorrectAnswerAndPreferSameKind() {
        fun w(word: String, title: String, type: String = "verb", kanji: List<String> = listOf(word.take(1))) = WordCard(word, word, title, type, kanji)
        val target = w("開ける", "open something")
        val all = listOf(
            target, w("開く", "open (by itself)", kanji = listOf("開")), w("閉める", "close something"), w("空ける", "open something"),
            w("犬", "dog", "noun"), w("猫", "cat", "noun"), w("木", "tree", "noun"),
        )
        repeat(20) { seed ->
            val o = WordQuizBuilder.options(target, all, WordDirection.JpToEn, Random(seed))
            assertEquals(4, o.size)
            assertEquals(1, o.count { it.word == "開ける" })
            assertEquals(o.size, o.map { it.label }.toSet().size)
            assertFalse(o.any { it.word == "空ける" })
            assertTrue(o.any { it.word == "開く" } && o.any { it.word == "閉める" })
        }
        assertEquals("開ける (あける)", WordQuizBuilder.label(WordCard("開ける", "あける", "x", null, emptyList()), WordDirection.EnToJp))
        assertEquals("ねこ", WordQuizBuilder.label(WordCard("ねこ", "ねこ", "cat", null, emptyList()), WordDirection.EnToJp))
    }

    @Test
    fun wordLevelMetadataDoesNotChangeQuizOptions() {
        val words = (1..6).map {
            WordCard("word-$it", "reading-$it", "meaning-$it", null, emptyList(), tags = listOf("free-tag-${it % 2}"))
        }
        val labelled = words.mapIndexed { i, word -> word.copy(jlpt = i % 5 + 1) }
        for (direction in WordDirection.entries) {
            assertEquals(
                WordQuizBuilder.options(words.first(), words, direction, Random(42)),
                WordQuizBuilder.options(labelled.first(), labelled, direction, Random(42)),
            )
        }
    }

    private fun optionWord(id: String, exclusions: List<String> = emptyList(), tags: List<String> = emptyList()) =
        WordCard(id, id, "meaning-$id", "noun", emptyList(), tags, quizExclusions = exclusions)

    @Test
    fun oneSidedQuizExclusionsApplyToEitherTargetInBothDirections() {
        val a = optionWord("気", listOf("気分"))
        val b = optionWord("気分")
        val all = listOf(a, b) + (1..4).map { optionWord("other-$it") }
        for (direction in meaningDirections) {
            for ((target, excluded) in listOf(a to b, b to a)) {
                repeat(20) { seed ->
                    val options = WordQuizBuilder.options(target, all, direction, Random(seed))
                    assertEquals(4, options.size)
                    assertEquals(1, options.count { it.word == target.word })
                    assertFalse(options.any { it.word == excluded.word })
                }
            }
        }
    }

    @Test
    fun excludedPairsCannotAppearTogetherAmongDistractorsInEitherSelectionOrder() {
        val target = optionWord("target", tags = listOf("kind"))
        val a = optionWord("a", listOf("b"), listOf("kind"))
        val b = optionWord("b", tags = listOf("kind"))
        val all = listOf(target, a, b, optionWord("c"), optionWord("d"))
        val seen = mutableSetOf<String>()
        for (direction in meaningDirections) {
            repeat(40) { seed ->
                val options = WordQuizBuilder.options(target, all, direction, Random(seed))
                assertEquals(4, options.size)
                val pair = options.filter { it.word in listOf("a", "b") }
                assertEquals(1, pair.size)
                seen += pair.single().word
            }
        }
        assertEquals(setOf("a", "b"), seen)
    }

    @Test
    fun quizExclusionsAreNotTransitive() {
        val a = optionWord("a", listOf("b"))
        val b = optionWord("b", listOf("c"))
        val c = optionWord("c")
        for (direction in meaningDirections) {
            for (target in listOf(a, c)) {
                assertEquals(setOf("a", "c"), WordQuizBuilder.options(target, listOf(a, b, c), direction, Random(1)).map { it.word }.toSet())
            }
        }
    }

    @Test
    fun excludedCandidatesDoNotReserveTitlesAndExistingExactDedupRemains() {
        val target = optionWord("target", listOf("blocked"), listOf("first", "second"))
        val blocked = optionWord("blocked", tags = target.tags)
        val eligible = optionWord("eligible", tags = listOf("first")).copy(title = blocked.title)
        val other = optionWord("other")
        val all = listOf(target, blocked, eligible, other, other.copy(title = "different"), optionWord("same-title").copy(title = target.title))
        for (direction in meaningDirections) {
            repeat(20) { seed ->
                val options = WordQuizBuilder.options(target, all, direction, Random(seed), count = 8)
                assertEquals(setOf("target", "eligible", "other"), options.map { it.word }.toSet())
                assertEquals(3, options.size)
            }
        }
    }

    @Test
    fun eligibleCandidatesRetainTheirExistingScorePriority() {
        val target = optionWord("target", listOf("blocked"), listOf("first", "second"))
        val blocked = optionWord("blocked", tags = target.tags)
        val high = optionWord("high", tags = listOf("first"))
        val low = optionWord("low")
        for (direction in meaningDirections) {
            repeat(20) { seed ->
                assertEquals(
                    setOf("target", "high"),
                    WordQuizBuilder.options(target, listOf(target, blocked, low, high), direction, Random(seed), count = 2).map { it.word }.toSet(),
                )
            }
        }
    }

    @Test
    fun smallPoolsNeverReintroduceExcludedOptionsEvenWhenOnlyTheTargetRemains() {
        val a = optionWord("a", listOf("b"))
        val b = optionWord("b")
        val target = optionWord("target")
        val clique = listOf(target, a.copy(quizExclusions = listOf("b", "c")), b.copy(quizExclusions = listOf("c")), optionWord("c"))
        for (direction in meaningDirections) {
            repeat(20) { seed ->
                assertEquals(listOf("a"), WordQuizBuilder.options(a, listOf(a, b), direction, Random(seed)).map { it.word })
                assertEquals(2, WordQuizBuilder.options(target, clique, direction, Random(seed)).size)
            }
            assertEquals(listOf("target"), WordQuizBuilder.options(target, clique, direction, Random(1), count = 1).map { it.word })
        }
    }

    @Test
    fun advancementNeedsBreadthAcrossModes() {
        val solid = review(30.0, lastDaysAgo = 2)
        val weak = review(1.0, lastDaysAgo = 5)
        val strongMode = List(40) { solid }
        assertTrue(Advancement.ready(listOf(strongMode, strongMode), now))
        assertFalse(Advancement.ready(listOf(strongMode, List(40) { null }), now))
        assertFalse(Advancement.ready(listOf(List(5) { solid }), now))
        assertTrue(Advancement.ready(listOf(strongMode, emptyList()), now))
        assertFalse(Advancement.ready(listOf(strongMode, List(20) { weak }), now))
        val day = 24 * 3600 * 1000L
        assertTrue(Advancement.shouldOffer(true, false, 0, 100 * day))
        assertFalse(Advancement.shouldOffer(true, false, 98 * day, 100 * day))
        assertFalse(Advancement.shouldOffer(true, true, 0, 100 * day))
        assertFalse(Advancement.shouldOffer(false, false, 0, 100 * day))
    }

    @Test
    fun readingOptionsAreDistinctAndIncludeLookAlikes() {
        fun w(word: String, reading: String, kanji: List<String>) = WordCard(word, reading, "x$word", null, kanji)
        val target = w("学校", "がっこう", listOf("学", "校"))
        val all = listOf(target, w("学生", "がくせい", listOf("学", "生")), w("先生", "せんせい", listOf("先", "生")), w("今日", "きょう", listOf("今", "日")), w("犬", "いぬ", listOf("犬")), w("ねこ", "ねこ", emptyList()), w("学", "がっこう", listOf("学")))
        repeat(30) { seed ->
            val o = WordQuizBuilder.options(target, all, WordDirection.Reading, Random(seed))
            assertEquals(4, o.size)
            assertEquals(4, o.map { it.word }.toSet().size)
            assertEquals(1, o.count { it.word == "がっこう" })
            assertTrue(o.all { it.word == it.label })
            assertFalse(o.any { it.word == "ねこ" })
            assertTrue(o.count { it.word in ReadingOptions.mutations("がっこう") } >= 2)
        }
    }

    @Test
    fun meaningExclusionsDoNotChangeDistinctReadingOptions() {
        val target = WordCard("青", "あお", "blue", "wago", listOf("青"), jlpt = 5, quizExclusions = listOf("青い"))
        val adjective = target.copy(word = "青い", reading = "あおい", title = "blue; green", quizExclusions = emptyList())
        val words = listOf(target, adjective, WordCard("森", "もり", "forest", "wago", listOf("森")))
        val withoutExclusions = words.map { it.copy(quizExclusions = emptyList()) }
        repeat(20) { seed ->
            val options = WordQuizBuilder.options(target, words, WordDirection.Reading, Random(seed))
            assertEquals(
                WordQuizBuilder.options(withoutExclusions.first(), withoutExclusions, WordDirection.Reading, Random(seed)),
                options,
            )
            assertEquals(4, options.size)
            assertEquals(options.size, options.map { it.word }.toSet().size)
            assertEquals(1, options.count { it.word == target.reading })
            assertTrue(options.any { it.word == adjective.reading })
        }
    }

    @Test
    fun mutationsCoverVoicingSokuonAndLongVowel() {
        val m = ReadingOptions.mutations("がっこう")
        assertTrue("かっこう" in m)
        assertTrue("がこう" in m)
        assertTrue("がっこ" in m)
        assertTrue("きょう" !in m && "がっこう" !in m)
        assertTrue("きょ" in ReadingOptions.mutations("きょう"))
        assertTrue(ReadingOptions.mutations("あ").isEmpty())
    }

    @Test
    fun readingDirectionIsGatedAndKanaOnlyWordsAreSkipped() {
        assertTrue(WordDirection.Reading.gated && WordDirection.EnToJp.gated && !WordDirection.JpToEn.gated)
        assertEquals(WordDirection.JpToEn, WordDirection.Reading.other)
        assertFalse(hasReadingToLearn("ねこ", "ねこ"))
        assertTrue(hasReadingToLearn("犬", "いぬ"))
        val jp = mapOf("a" to review(2.0, reps = 2, dueOffsetSec = 99999), "b" to review(2.0, reps = 1))
        val q = WordQueues.build(WordDirection.Reading, listOf("a", "b", "c"), emptyMap(), jp, now, newBudget = 10, noise = 0.0)
        assertEquals(listOf("a"), q.map { it.kanji })
    }}
