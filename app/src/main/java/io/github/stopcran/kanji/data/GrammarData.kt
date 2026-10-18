package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GRAMMAR_NOT_DUE
import io.github.stopcran.kanji.core.grammar.GrammarEligibility
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.serialization.json.Json
import java.time.Instant

const val GRAMMAR_STACK = "grammar"
const val GRAMMAR_LEVEL_EPOCH = 1

private val grammarJson = Json { ignoreUnknownKeys = true }

suspend fun AppDatabase.grammarSets(sourceId: String): List<GrammarSet> =
    content().grammar(sourceId).map {
        grammarJson.decodeFromString(GrammarSet.serializer(), it.json)
    }

suspend fun AppDatabase.grammarStates(sourceId: String): Map<String, SrsState> =
    reviews().states(sourceId, GRAMMAR_STACK, StudyMode.Grammar.name).associate { it.kanji to it.toSrs() }

suspend fun AppDatabase.grammarModeState(
    sourceId: String,
    sets: List<GrammarSet>,
    newBudget: Int,
    now: Instant,
    preferences: GrammarPreferences,
): ModeState {
    val exercises = sets.flatMap { it.exercises }
    val states = grammarStates(sourceId)
    val pool = GrammarEligibility.pool(exercises, states, preferences.newScope, preferences.levelsReady)
    val queue = QueueBuilder.build(pool.ids, states, now, newBudget, noise = 0.0)
    val extra = QueueBuilder.extra(pool.ids, states, now, limit = 1, noise = 0.0)
    return ModeState.of(
        available = exercises.isNotEmpty(),
        unavailableReason = "No grammar exercises yet. Sync the content first (⚙ settings).",
        due = queue.count { !it.isNew },
        new = queue.count { it.isNew },
        extraPossible = extra.isNotEmpty(),
        lockedReason = pool.newItemMessage ?: GRAMMAR_NOT_DUE,
    )
}
