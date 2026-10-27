package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.kana.KANA_SOURCE_ID
import io.github.stopcran.kanji.core.srs.DayLevel
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.Pacing
import io.github.stopcran.kanji.core.srs.WeekProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId

private const val CAPACITY_WINDOW_DAYS = 14L

/** Local study dates from built-in Kana and the selected repository; daily allowances remain separate. */
fun AppDatabase.observeWeekProgress(settings: Settings, sourceId: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Flow<WeekProgress> {
    val today = now.atZone(zone).toLocalDate()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val times = reviews().observeReviewTimes(
        listOf(sourceId, KANA_SOURCE_ID).distinct(),
        monday.atStartOfDay(zone).toInstant().toEpochMilli(),
        monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(),
    )
    return combine(times, settings.weekPlan) { reviews, plan ->
        val studied = reviews.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toSet().size
        WeekProgress(studied, Pacing.weeklyGoal(DayLevel.parseWeek(plan)))
    }.distinctUntilChanged()
}

/** Today's shared allowance of new items across all modes, from the weekday plan, the base amount and the current backlog. */
suspend fun AppDatabase.newAllowance(settings: Settings, sourceId: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): NewAllowance {
    val today = now.atZone(zone).toLocalDate()
    val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val r = reviews()
    val perDay = r.reviewTimesSince(sourceId, today.minusDays(CAPACITY_WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli(),
        excludedMode = io.github.stopcran.kanji.core.listening.LISTENING_MODE)
        .groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()
        .filterKeys { it != today }.values.toList()
    val level = DayLevel.parseWeek(settings.weekPlan.value)[today.dayOfWeek.value - 1]
    val allowance = Pacing.allowance(
        base = settings.dailyNewCards.value, level = level,
        introducedToday = r.itemsIntroducedSince(sourceId, startOfDay),
        due = r.dueCount(sourceId, now.toEpochMilli(), excludedMode = io.github.stopcran.kanji.core.listening.LISTENING_MODE),
        capacity = Pacing.capacity(perDay),
    )
    return allowance.copy(remaining = (allowance.remaining - listening().reservedNew(sourceId) -
        listeningTasks().reservedNew(sourceId)).coerceAtLeast(0))
}
