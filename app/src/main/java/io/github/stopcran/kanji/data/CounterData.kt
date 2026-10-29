package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.counters.CounterIndex
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterQueueRequest
import io.github.stopcran.kanji.core.counters.CounterQueues
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.SrsState
import kotlinx.serialization.json.Json
import java.time.Instant

/** Counter cards share one stack, so their review state is never mixed with kanji or word stacks. */
const val COUNTER_STACK = "counters"

private val counterJson = Json { ignoreUnknownKeys = true }

/** Decodes the stored counter files; a file that fails to decode is skipped rather than breaking the others. */
suspend fun AppDatabase.counterIndex(sourceId: String): CounterIndex =
    CounterIndex(
        content().counters(sourceId).mapNotNull { e ->
            runCatching { counterJson.decodeFromString(CounterFile.serializer(), e.json) }.getOrNull()
        },
    )

suspend fun AppDatabase.counterStates(sourceId: String, mode: CounterMode): Map<String, SrsState> =
    reviews().states(sourceId, COUNTER_STACK, mode.mode.name).associate { it.kanji to it.toSrs() }

/** Reminder opportunities include due and permitted new cards, under the source's saved level. */
suspend fun AppDatabase.counterWaitingCount(settings: Settings, sourceId: String, now: Instant, newBudget: Int): Int {
    val index = counterIndex(sourceId).inScope(scope = settings.counterScopeFor(sourceId))
    val choose = counterStates(sourceId, CounterMode.Choose)
    return CounterMode.entries.sumOf { mode ->
        val own = if (mode == CounterMode.Choose) choose else counterStates(sourceId, mode)
        CounterQueues.build(index, CounterQueueRequest(mode, own, choose, now, newBudget)).size
    }
}

/** Home rows of the Counters page, computed from the same queue builder the quiz uses. */
suspend fun AppDatabase.counterModeStates(sourceId: String, index: CounterIndex, newBudget: Int, now: Instant): Map<CounterMode, ModeState> {
    val choose = counterStates(sourceId, CounterMode.Choose)
    return CounterMode.entries.filter { index.ids(it).isNotEmpty() }.associateWith { mode ->
        val own = if (mode == CounterMode.Choose) choose else counterStates(sourceId, mode)
        val queue = CounterQueues.build(index, CounterQueueRequest(mode, own, choose, now, newBudget))
        val extra = CounterQueues.build(index, CounterQueueRequest(mode, own, choose, now, newBudget, extraLimit = 1))
        ModeState.of(
            available = index.ids(mode).isNotEmpty(),
            unavailableReason = "No counter cards yet. Sync the content first (⚙ settings).",
            due = queue.count { !it.isNew },
            new = queue.count { it.isNew },
            extraPossible = extra.isNotEmpty(),
            lockedReason = when (mode) {
                CounterMode.Type -> "Opens for sentences you have answered in “Choose the counter” on two different days."
                CounterMode.Noun -> "Opens for a counter once three of its sentences are solid in “Choose the counter”."
                CounterMode.Choose, CounterMode.Expression, CounterMode.Reading ->
                    "Nothing is due. Come back later or raise the daily new-card limit in ⚙ settings."
            },
        )
    }
}
