package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.DayLevel
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

internal data class ListeningQueue(
    val passages: List<ListeningEntity>, val tasks: List<ListeningTaskEntity>, val states: Map<String, SrsState>,
    val due: List<String>, val fresh: List<String>, val extra: List<String>, val blocked: Map<String, String>,
    val cooldowns: Map<String, Instant>, val allowance: ListeningAllowance, val shared: NewAllowance, val minimumNewMs: Long?,
)

private class ListeningCandidates(private val exposure: Map<String, Long?>, private val now: Instant) {
    val blocked = mutableMapOf<String, String>()
    val cooldowns = mutableMapOf<String, Instant>()
    val estimates = mutableMapOf<String, Long>()

    fun add(id: String, exposureId: String?, ready: Boolean, reason: String?, estimate: Long?) {
        val last = exposure[exposureId]
        when {
            !ready -> blocked[id] = reason ?: "Listening content is not ready"
            last != null && now.toEpochMilli() < last + ListeningPolicy.COOLDOWN_MS ->
                cooldowns[id] = Instant.ofEpochMilli(last + ListeningPolicy.COOLDOWN_MS)
            else -> estimates[id] = requireNotNull(estimate) { "Approved listening content has no time estimate" }
        }
    }
}

internal suspend fun AppDatabase.listeningQueue(
    settings: Settings, sourceId: String, now: Instant, zone: ZoneId,
    supportedTypes: Set<ListeningTaskType> = ListeningTaskPolicy.supportedTypes,
): ListeningQueue {
    val legacy = listening().passages(sourceId)
    val tasks = listeningTasks().tasks(sourceId)
    val states = reviews().states(sourceId, LISTENING_STACK, LISTENING_MODE).filter { it.reading.isEmpty() }
        .associate { it.kanji to it.toSrs() }
    val exposure = listening().exposures(sourceId).associate { it.passageId to it.lastPracticeAtMs }
    val candidates = ListeningCandidates(exposure, now)
    val blocked = candidates.blocked
    val cooldowns = candidates.cooldowns
    val estimates = candidates.estimates
    legacy.forEach { candidates.add(it.id, it.id, it.ready, it.reason, it.durationMs?.let(ListeningPolicy::estimatedMs)) }
    val supported = supportedTypes.mapTo(hashSetOf()) { it.wire }
    tasks.filter { it.usage != TaskUsage.Example.name }.forEach {
        candidates.add(it.id, it.exposureId, it.ready && it.taskType in supported,
            if (it.ready && it.taskType !in supported) "This app cannot present this task type yet" else it.reason, it.estimatedMs)
    }
    val known = legacy.mapTo(hashSetOf()) { it.id } + tasks.map { it.id }
    states.filter { (id, state) -> id !in known && !state.due.isAfter(now) }.forEach { (id, _) ->
        blocked[id] = "Previously started listening item is unavailable in this content snapshot"
    }
    val due = QueueBuilder.build(estimates.keys.toList(), states, now, newCardsRemainingToday = 0).map { it.kanji }
    val today = now.atZone(zone).toLocalDate()
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val legacyIntroductions = listening().introductions(sourceId, start)
    val taskIntroductions = listeningTasks().introductions(sourceId, start)
    val shared = newAllowance(settings, sourceId, now, zone)
    val day = DayLevel.parseWeek(settings.weekPlan.value)[today.dayOfWeek.value - 1]
    val allowance = ListeningPolicy.allowance(shared.remaining, day,
        legacyIntroductions.count + taskIntroductions.count, legacyIntroductions.estimatedMs + taskIntroductions.estimatedMs,
        due.sumOf { estimates.getValue(it) })
    val unseen = estimates.keys.filter { states[it]?.phase.let { phase -> phase == null || phase == CardPhase.New } }
    var remaining = allowance.milliseconds
    val fresh = unseen.shuffled(Random("$sourceId|$today".hashCode())).filter { id ->
        val estimate = estimates.getValue(id)
        (estimate <= remaining).also { if (it) remaining -= estimate }
    }.take(allowance.count)
    return ListeningQueue(legacy, tasks, states, due, fresh,
        QueueBuilder.extra(estimates.keys.toList(), states, now, limit = Int.MAX_VALUE, noise = 0.0).map { it.kanji },
        blocked, cooldowns, allowance, shared, unseen.minOfOrNull { estimates.getValue(it) })
}

internal suspend fun AppDatabase.recordListeningExposure(source: String, exposureId: String, first: Long, last: Long) {
    val old = listeningTasks().exposure(source, exposureId)
    listeningTasks().putExposure(ListeningExposureEntity(source, exposureId,
        minOf(first, old?.firstAtMs ?: first), maxOf(last, old?.lastAtMs ?: last)))
}

internal suspend fun AppDatabase.requireNoOtherListeningActivity(source: String) {
    require(listening().active(source) == null && listeningTasks().active(source) == null &&
        listeningTasks().activeAssessment(source) == null) { "Resume or discard the unfinished listening activity first" }
}
