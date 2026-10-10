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

suspend fun AppDatabase.readingData(sourceId: String, stack: String, kanji: List<KanjiEntity>, newRemaining: Int, now: Instant, extra: Boolean): ReadingData {
    val cards = kanji.map { it.toReadingCard() }
    val states = ReadingKind.entries.associateWith { kind -> reviews().states(sourceId, stack, kind.mode.name).associate { it.kanji to it.toSrs() } }
    val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val introducedToday = reviews().readingKanjiIntroducedSince(sourceId, stack, startOfDay).toSet()
    val items = KanjiReadingQueue.build(
        cards, states,
        unlocked = reviews().meaningLearned(sourceId, stack).toSet(),
        introducedToday = introducedToday,
        // The queue subtracts kanji already started today; the shared allowance has already accounted for them.
        dailyNew = newRemaining + introducedToday.size, now = now, extra = extra,
    )
    return ReadingData(cards, states, items)
}
