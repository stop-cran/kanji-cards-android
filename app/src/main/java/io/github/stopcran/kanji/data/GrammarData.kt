package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.serialization.json.Json
import java.time.Instant

const val GRAMMAR_STACK = "grammar"

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
): ModeState {
    val ids = sets.flatMap { it.exercises }.map { it.id }
    val states = grammarStates(sourceId)
    val queue = QueueBuilder.build(ids, states, now, newBudget, noise = 0.0)
    val extra = QueueBuilder.extra(ids, states, now, limit = 1, noise = 0.0)
    return ModeState.of(
        available = ids.size >= 2,
        unavailableReason = "No grammar exercises yet. Sync the content first (⚙ settings).",
        due = queue.count { !it.isNew },
        new = queue.count { it.isNew },
        extraPossible = extra.isNotEmpty(),
        lockedReason = "Nothing is due. Come back later or raise the daily new-item limit in ⚙ settings.",
    )
}
