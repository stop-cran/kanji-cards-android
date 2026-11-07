package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.quiz.QuizBuilder
import io.github.stopcran.kanji.core.quiz.QuizCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizBuilderTest {
    private fun card(k: String, title: String, tags: List<String> = emptyList(), d: List<String> = emptyList()) = QuizCard(k, title, tags, d)

    private val all = listOf(
        card("a", "sun", listOf("n5", "g1"), listOf("b")),
        card("b", "moon", listOf("n5")),
        card("c", "tree", listOf("n5", "g1")),
        card("d", "fire", listOf("n3")),
        card("e", "water", listOf("n3")),
        card("f", "sun", listOf("n5")),
    )

    @Test
    fun containsTargetOnceAndUniqueTitles() {
        repeat(50) { seed ->
            val o = QuizBuilder.options(all[0], all, Random(seed))
            assertEquals(4, o.size)
            assertEquals(1, o.count { it.kanji == "a" })
            assertEquals(o.size, o.map { it.title }.toSet().size)
        }
    }

    @Test
    fun usesConfiguredDistractorsFirst() {
        repeat(20) { seed -> assertTrue(QuizBuilder.options(all[0], all, Random(seed)).any { it.kanji == "b" }) }
    }

    @Test
    fun smallDeckGivesFewerOptions() {
        val o = QuizBuilder.options(all[0], all.take(2), Random(1))
        assertEquals(listOf("a", "b"), o.map { it.kanji }.sorted())
    }

    @Test
    fun symmetricExclusionsApplyToCuratedFallbackAndTargetsOutsidePool() {
        val left = card("剰", "remainder; surplus amount", d = listOf("冗", "missing"))
            .copy(quizExclusions = listOf("冗"))
        val right = card("冗", "superfluous; redundant", d = listOf("剰"))
        val safe = listOf(card("剣", "sword"), card("月", "moon"), card("日", "sun"))
        for ((target, excluded) in listOf(left to right, right to left)) {
            for (pool in listOf(safe + excluded + target, safe + excluded, listOf(excluded), emptyList())) {
                repeat(16) { seed ->
                    val options = QuizBuilder.options(target, pool, Random(seed))
                    assertTrue(options.any { it.kanji == target.kanji })
                    assertTrue(options.none { it.kanji == excluded.kanji })
                    assertEquals(minOf(4, pool.count { it in safe } + 1), options.size)
                }
            }
        }
    }

    @Test
    fun exclusionsAmongWrongChoicesAreDirectNotTransitiveAndKeepCuratedPreference() {
        val a = card("a", "A", d = listOf("b", "c", "missing", "d"))
        val b = card("b", "B").copy(quizExclusions = listOf("c"))
        val c = card("c", "C").copy(quizExclusions = listOf("d"))
        val d = card("d", "D")
        val pool = listOf(a, b, c, d)
        repeat(16) { seed ->
            assertEquals(setOf("a", "b", "d"), QuizBuilder.options(a, pool, Random(seed)).map { it.kanji }.toSet())
            val fallback = QuizBuilder.options(a.copy(distractors = emptyList()), pool, Random(seed))
            val ids = fallback.map { it.kanji }
            assertTrue("b" !in ids || "c" !in ids)
            assertTrue("c" !in ids || "d" !in ids)
        }
        assertEquals(setOf("b", "d"), QuizBuilder.options(b, listOf(b, d), Random(4)).map { it.kanji }.toSet())
    }

    @Test
    fun native733FrozenRandomN1SubsetSeeds4And7ExposeLegacyPairAndExcludeItWithMetadata() {
        // learning-japanese 17a4bbb997c1940dc802cf4e97599c560009e4a7, batch-099-native:
        // N1Batch099NativeTest.kt's Random(732) subset, reconstructed from that snapshot's sorted 131 N1 cards.
        // Membership/order was not captured in JSON; reconstruction matched ALL 16 captured choice lists in
        // execution-733/raw/n1-099-article-route.json. These two lists are the original saved outputs, not new seeds.
        val frozen = listOf(
            Triple("剰", "remainder; surplus amount", "乗 剣 則"),
            Triple("剣", "sword; double-edged blade", "剛 剖 則"),
            Triple("刈", "mow; reap", "乃 凹 凸"),
            Triple("亥", "Boar zodiac sign", "刻 交 亡"),
            Triple("繭", "cocoon", "練 線 綿"),
            Triple("儒", "Confucian; Confucian scholar", "儀 僧 優"),
            Triple("凜", "dignified; bracing cold", "准 冗 凹"),
            Triple("妖", "bewitching; supernatural", "妹 笑 天"),
            Triple("乃", "thereupon; possessive no", "九 万 力"),
            Triple("扱", "handle; deal with", "吸 坂 投"),
            Triple("鯉", "carp", "理 量 恋"),
            Triple("丞", "assist; aid", "蒸 乗 水"),
            Triple("鶏", "chicken", "魚 駅 黒"),
            Triple("冗", "superfluous; redundant", "元 兄 見"),
            Triple("丘", "hill; knoll", "兵 近 共"),
            Triple("丑", "Ox zodiac sign", "五 互 土"),
        ).map { (id, title, distractors) ->
            card(id, title, listOf("jlpt-n1", if (id in "亥凜乃鯉丞丑") "grade-9" else "grade-8",
                "structure-unclassified"), distractors.split(" "))
        }
        val saved = mapOf(4 to listOf("冗", "刈", "剰", "剣"), 7 to listOf("儒", "剰", "冗", "剣"))
        for ((seed, expected) in saved) {
            assertEquals(expected, QuizBuilder.options(frozen.first(), frozen, Random(seed)).map { it.kanji })
            for (declarer in listOf("剰", "冗")) {
                val guarded = frozen.map {
                    if (it.kanji == declarer) it.copy(quizExclusions = listOf(if (declarer == "剰") "冗" else "剰")) else it
                }
                assertFrozenGuard(guarded, seed)
            }
        }
    }

    private fun assertFrozenGuard(guarded: List<QuizCard>, seed: Int) {
        for (id in listOf("剰", "冗")) {
            val target = guarded.single { it.kanji == id }
            for (pool in listOf(guarded, guarded.filter { it != target })) {
                val options = QuizBuilder.options(target, pool, Random(seed))
                assertEquals(4, options.size)
                assertTrue(options.any { it.kanji == id })
                assertTrue(options.none { it.kanji == if (id == "剰") "冗" else "剰" })
                assertEquals(options.size, options.map { it.title }.distinct().size)
            }
        }
    }
}
