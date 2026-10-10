package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.srs.DayLevel
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.Pacing
import java.time.Instant
import java.time.ZoneId

private const val CAPACITY_WINDOW_DAYS = 14L

/** Today's shared allowance of new items across all modes, from the weekday plan, the base amount and the current backlog. */
suspend fun AppDatabase.newAllowance(settings: Settings, sourceId: String, now: Instant, zone: ZoneId = ZoneId.systemDefault()): NewAllowance {
    val today = now.atZone(zone).toLocalDate()
    val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val r = reviews()
    val perDay = r.reviewTimesSince(sourceId, today.minusDays(CAPACITY_WINDOW_DAYS).atStartOfDay(zone).toInstant().toEpochMilli())
        .groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.eachCount()
        .filterKeys { it != today }.values.toList()
    val level = DayLevel.parseWeek(settings.weekPlan.value)[today.dayOfWeek.value - 1]
    return Pacing.allowance(
        base = settings.dailyNewCards.value, level = level,
        introducedToday = r.itemsIntroducedSince(sourceId, startOfDay),
        due = r.dueCount(sourceId, now.toEpochMilli()), capacity = Pacing.capacity(perDay),
    )
}
