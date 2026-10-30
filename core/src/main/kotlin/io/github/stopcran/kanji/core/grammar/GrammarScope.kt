package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState

enum class GrammarScope(val id: String, val label: String, private val level: Int = 0) {
    All("all", "All"),
    Unclassified("unclassified", "Unclassified"),
    N5("n5", "N5", 5),
    N4("n4", "N4", 4),
    N3("n3", "N3", 3),
    N2("n2", "N2", 2),
    N1("n1", "N1", 1);

    val introductionLabel: String get() = if (level == 0) label else "$label and easier"
    val browseLabel: String get() = if (level == 0) label else "$label only"

    fun matches(exerciseLevel: Int?, cumulative: Boolean): Boolean = when (this) {
        All -> true
        Unclassified -> exerciseLevel == null
        else -> exerciseLevel != null && if (cumulative) exerciseLevel >= level else exerciseLevel == level
    }

    companion object {
        fun fromId(id: String): GrammarScope =
            requireNotNull(entries.find { it.id == id }) { "Unknown grammar scope '$id'." }
    }
}

const val GRAMMAR_LEVEL_SYNC_NEEDED = "Sync needed for level-filtered new material (settings)."
const val GRAMMAR_NOT_DUE = "Nothing is due. Come back later or raise the daily new-item limit in settings."

data class GrammarPool(val ids: List<String>, val newItemMessage: String?)

object GrammarEligibility {
    fun pool(
        exercises: List<GrammarExercise>,
        states: Map<String, SrsState>,
        scope: GrammarScope,
        levelsReady: Boolean,
    ): GrammarPool {
        fun started(id: String) = states[id]?.phase?.let { it != CardPhase.New } == true
        val canFilter = levelsReady || scope == GrammarScope.All
        val eligible = exercises.filter {
            started(it.id) || (canFilter && scope.matches(it.jlpt, cumulative = true))
        }
        val message = when {
            !canFilter -> GRAMMAR_LEVEL_SYNC_NEEDED
            scope != GrammarScope.All && eligible.none { !started(it.id) } ->
                "No unseen grammar exercises match ${scope.introductionLabel}."
            else -> null
        }
        return GrammarPool(eligible.map { it.id }, message)
    }
}
