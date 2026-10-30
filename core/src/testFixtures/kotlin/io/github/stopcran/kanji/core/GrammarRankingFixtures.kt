package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.GrammarRecommendation
import io.github.stopcran.kanji.core.content.GrammarSet

fun GrammarSet.recommending(vararg links: Pair<String, Int>): GrammarSet = copy(
    introductionGroups = listOf(introductionGroups.single().copy(
        next = links.map { (target, importance) -> GrammarRecommendation(target, importance, "Compare $id with $target.") },
    )),
)
