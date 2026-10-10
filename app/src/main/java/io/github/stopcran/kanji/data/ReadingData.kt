package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.reading.KanjiReadingQueue
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingItem
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.SrsState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun KanjiEntity.toReadingCard() = ReadingCard(kanji, onyomi.splitSep(), kunyomi.splitSep(), distractors = distractors.splitSep())

/** Everything the readings quiz and reminders need for one stack: scheduling state per kind and the resulting queue. */
class ReadingData(val cards: List<ReadingCard>, val states: Map<ReadingKind, Map<String, SrsState>>, val items: List<ReadingItem>)

/** Which stack the readings are built for, how many new cards may start, and whether this is the extra "practice more" queue. */
data class ReadingRequest(val sourceId: String, val stack: String, val newRemaining: Int, val now: Instant, val extra: Boolean = false)

suspend fun AppDatabase.readingData(settings: Settings, kanji: List<KanjiEntity>, request: ReadingRequest): ReadingData {
    val sourceId = request.sourceId
    val stack = request.stack
    val newRemaining = request.newRemaining
    val now = request.now
    val extra = request.extra
    val cards = kanji.map { it.toReadingCard() }
    val states = ReadingKind.entries.associateWith { kind -> reviews().states(sourceId, stack, kind.mode.name).toReadingStates() }
    val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val introducedToday = reviews().readingCardsIntroducedSince(sourceId, stack, startOfDay)
    val items = KanjiReadingQueue.build(
        cards, states,
        unlocked = meaningLearned(settings, sourceId, stack),
        introducedToday = introducedToday,
        // The queue subtracts the reading cards already started today; the shared allowance has already accounted for them.
        dailyNew = newRemaining + introducedToday, now = now, extra = extra,
    )
    return ReadingData(cards, states, items)
}
