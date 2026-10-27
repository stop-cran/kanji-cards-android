package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.kana.KANA_SOURCE_ID
import io.github.stopcran.kanji.core.kana.KanaCatalog
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant

internal data class KanaStateKey(val script: KanaScript, val mode: KanaMode)
private data class KanaStateSnapshot(val key: KanaStateKey, val rows: List<ReviewStateEntity>)

internal class KanaOverview(
    private val states: Map<KanaStateKey, List<ReviewStateEntity>>,
    val allowance: NewAllowance?,
    private val now: Instant,
) {
    fun lockedCount(script: KanaScript, mode: KanaMode): Int? {
        val rows = states[KanaStateKey(script, mode)] ?: return null
        val byId = rows.associate { it.kanji to it.toSrs() }
        return KanaCatalog.all(script).size - KanaCatalog.unlocked(script, byId).size
    }

    fun modeState(script: KanaScript, mode: KanaMode): ModeState {
        val budget = allowance?.remaining ?: return ModeState.Loading
        val byId = states[KanaStateKey(script, mode)].orEmpty().associate { it.kanji to it.toSrs() }
        val ids = KanaCatalog.unlocked(script, byId).map { it.kana }
        val queue = QueueBuilder.build(ids, byId, now, budget, noise = 0.0)
        val extra = QueueBuilder.extra(ids, byId, now, limit = 1, noise = 0.0).isNotEmpty()
        return ModeState.of(
            available = true,
            unavailableReason = "",
            due = queue.count { !it.isNew },
            new = queue.count { it.isNew },
            extraPossible = extra,
            lockedReason = "No kana remain in this script.",
        )
    }

    @Composable
    fun summary(): String {
        val budget = allowance?.remaining ?: return "Loading…"
        val due = KanaScript.entries.sumOf { script ->
            KanaMode.entries.sumOf { mode ->
                val map = states[KanaStateKey(script, mode)].orEmpty().associate { it.kanji to it.toSrs() }
                map.values.count { it.phase != io.github.stopcran.kanji.core.srs.CardPhase.New && !it.due.isAfter(now) }
            }
        }
        val availableNew = KanaScript.entries.sumOf { script ->
            KanaMode.entries.sumOf { mode ->
                val rows = states[KanaStateKey(script, mode)].orEmpty()
                val seenInMode = rows.mapTo(HashSet()) { it.kanji }
                KanaCatalog.unlocked(script, rows.associate { it.kanji to it.toSrs() }).count { it.kana !in seenInMode }
            }
        }
        return if (due == 0 && (budget == 0 || availableNew == 0)) "All caught up"
        else stringResource(R.string.study_due_new, due, minOf(budget, availableNew))
    }
}

@Composable
internal fun rememberKanaOverview(app: KanjiApp): KanaOverview {
    val streams = remember(app) {
        KanaScript.entries.flatMap { script ->
            KanaMode.entries.map { mode ->
                app.db.reviews().observeStates(KANA_SOURCE_ID, script.id, mode.name)
                    .map { KanaStateSnapshot(KanaStateKey(script, mode), it) }
            }
        }
    }
    val stateFlow = remember(streams) {
        combine(streams) { snapshots -> snapshots.associate { it.key to it.rows } }
    }
    val states by stateFlow.collectAsState(emptyMap())
    val dailyNew by app.settings.dailyNewCards.collectAsState()
    val weekPlan by app.settings.weekPlan.collectAsState()
    val now by produceState(Instant.now(), states, dailyNew, weekPlan) {
        while (true) {
            value = Instant.now()
            delay(60_000)
        }
    }
    val allowance by produceState<NewAllowance?>(null, states, dailyNew, weekPlan, now) {
        value = app.db.newAllowance(app.settings, KANA_SOURCE_ID, now)
    }
    return remember(states, allowance, now) { KanaOverview(states, allowance, now) }
}
