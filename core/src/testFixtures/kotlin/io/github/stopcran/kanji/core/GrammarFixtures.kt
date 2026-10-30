package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarFacet
import io.github.stopcran.kanji.core.content.GrammarIntroductionGroup
import io.github.stopcran.kanji.core.content.GrammarMember
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarEvent
import java.time.Instant

fun grammarFixture(id: String = "alpha", examples: Int = 3, authored: Boolean = true, requires: List<String> = emptyList()): GrammarSet {
    val targets = listOf("a", "b")
    val exercises = targets.flatMap { target ->
        (0 until examples).map { index ->
            GrammarExercise(
                "$id-$target-$index", target, "contrast", "Synthetic ____.", "Synthetic meaning.", "Evidence.", "Explanation.",
                target, listOf(target), targets.map { GrammarOption(it, it, "Rationale.") }, 4,
            )
        }
    }
    val group = GrammarIntroductionGroup(
        id, id, targets.map { GrammarFacet(it, it) },
        exercises.map { GrammarMember(it.id, it.target, it.id.substringAfterLast('-')) }, requires = requires,
    )
    return GrammarSet(
        1, id, id, "articles/x.md", targets.map { GrammarConstruction(it, it) },
        listOf(GrammarRelationship("contrast", "contrast", targets)), exercises,
        if (authored) listOf(group) else emptyList(),
    )
}

fun familiarEvents(set: GrammarSet, now: Instant): List<GrammarEvent> =
    set.exercises.filter { it.id.endsWith("-0") || it.id.endsWith("-1") }.mapIndexed { index, exercise ->
        GrammarEvent(
            index.toLong(), exercise.id, 3, now.minusSeconds(if (exercise.id.endsWith("-0")) 172_800 else 86_400).toEpochMilli(),
            exercise.target, exercise.relationship, if (exercise.id.endsWith("-0")) "choose" else "type", true, true,
        )
    }
