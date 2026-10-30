package io.github.stopcran.kanji.core.quiz

import kotlin.random.Random

data class QuizCard(val kanji: String, val title: String, val tags: List<String>, val distractors: List<String>)

data class QuizOption(val kanji: String, val title: String)

/**
 * Builds the answer options for the "guess the meaning" mode. Options are meaning titles; two options never share a title,
 * so there is exactly one correct answer. Preference order for wrong options: the card's own `distractors`, then cards with
 * the most shared tags (similar level/grade/structure), then anything else.
 */
object QuizBuilder {
    fun options(target: QuizCard, all: List<QuizCard>, random: Random, count: Int = 4): List<QuizOption> {
        val byKanji = all.associateBy { it.kanji }
        val chosen = LinkedHashMap<String, QuizCard>()
        val usedTitles = hashSetOf(target.title)

        fun tryAdd(c: QuizCard) {
            if (chosen.size < count - 1 && c.kanji != target.kanji && usedTitles.add(c.title)) chosen[c.kanji] = c
        }

        target.distractors.mapNotNull { byKanji[it] }.forEach(::tryAdd)
        val others = all.filter { it.kanji != target.kanji }.shuffled(random)
        others.sortedByDescending { c -> c.tags.count { it in target.tags } }.forEach(::tryAdd)

        return (chosen.values + target).map { QuizOption(it.kanji, it.title) }.shuffled(random)
    }
}
