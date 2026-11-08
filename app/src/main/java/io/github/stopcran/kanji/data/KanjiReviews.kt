package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.reading.KanjiReadingQuiz
import io.github.stopcran.kanji.core.reading.ReadingCard
import io.github.stopcran.kanji.core.reading.ReadingId
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.Fsrs
import io.github.stopcran.kanji.core.srs.Grade
import io.github.stopcran.kanji.core.srs.KanjiTransitionChecks
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import java.time.Instant
import kotlin.random.Random

/** A review, its log, and any transition check commit together. */
suspend fun AppDatabase.recordKanjiReview(state: ReviewStateEntity, log: ReviewLogEntity) {
    require(state.matches(log)) { "Review state and log identities differ" }
    val isReading = state.mode == StudyMode.KanjiOn.name || state.mode == StudyMode.KanjiKun.name
    if (state.stack != Stacks.JLPT_STATE && !isReading) {
        reviews().record(state, log)
        return
    }
    withTransaction {
        val dao = reviews()
        val previous = dao.states(state.sourceId, state.stack, state.mode)
        val alreadyStarted = previous.any { it.kanji == state.kanji && it.phase != "New" }
        val current = previous.firstOrNull { it.kanji == state.kanji && it.reading == state.reading }?.toSrs() ?: SrsState()
        val recordedLog = if (isReading) log.copy(previousReadingStarted = current.phase != CardPhase.New) else log
        val updated = nextKanjiState(state, log, current)
        dao.record(updated, recordedLog)
        if (state.stack == Stacks.JLPT_STATE) {
            dao.completeTransitionCheck(state.sourceId, state.kanji, state.mode, state.reading, log.atMs)
            if (!alreadyStarted && updated.phase != "New") scheduleTransitionCheck(updated, log.atMs, previous)
        }
    }
}

private fun ReviewStateEntity.matches(log: ReviewLogEntity): Boolean =
    sourceId == log.sourceId && stack == log.stack && kanji == log.kanji && mode == log.mode && reading == log.reading

private fun nextKanjiState(state: ReviewStateEntity, log: ReviewLogEntity, current: SrsState): ReviewStateEntity {
    val mode = StudyMode.entries.single { it.name == log.mode }
    val grade = Grade.entries.single { it.value == log.grade }
    val id = if (state.reading.isEmpty()) state.kanji else ReadingId.of(state.kanji, state.reading)
    val cap = if (mode == StudyMode.Quiz || mode == StudyMode.KanjiOn || mode == StudyMode.KanjiKun) {
        Fsrs.GUESSABLE_FIRST_SUCCESS_DAYS
    } else null
    return Fsrs().review(current, grade, Instant.ofEpochMilli(log.atMs), cap, Fsrs.seed(id, mode.name, current.reps))
        .toEntity(state.sourceId, state.stack, state.kanji, mode, state.reading)
}

private suspend fun AppDatabase.scheduleTransitionCheck(trigger: ReviewStateEntity, nowMs: Long, states: List<ReviewStateEntity>) {
    if (trigger.mode !in setOf(StudyMode.Quiz.name, StudyMode.Draw.name, StudyMode.KanjiOn.name, StudyMode.KanjiKun.name)) return
    val related = KanjiTransitionChecks.related(trigger.kanji)
    if (related.isEmpty()) return
    val targets = TransitionTargets(content().kanjiLite(trigger.sourceId))
    val checks = reviews().transitionChecks(trigger.sourceId, nowMs)
    val pending = checks.filter { it.completedAtMs == null }.mapTo(HashSet()) { Triple(it.kanji, it.mode, it.reading) }
    val candidates = states.filter { candidate ->
        candidate.kanji in related && candidate.phase != "New" &&
            targets.available(trigger.kanji, candidate.kanji, candidate.mode, candidate.reading) &&
            Triple(candidate.kanji, candidate.mode, candidate.reading) !in pending
    }.sortedWith(compareBy<ReviewStateEntity> { it.dueMs }.thenBy { it.kanji }.thenBy { it.reading })
    val reservations = checks.map { it.dueMs }
    val (candidate, due) = candidates.firstNotNullOfOrNull { candidate ->
        KanjiTransitionChecks.nextDue(nowMs, candidate.dueMs, reservations)?.let { candidate to it }
    } ?: return
    check(reviews().advanceDue(candidate.sourceId, candidate.stack, candidate.kanji, candidate.mode, candidate.reading, due) == 1)
    reviews().insertTransitionCheck(
        JlptTransitionCheckEntity(
            sourceId = candidate.sourceId, triggerKanji = trigger.kanji, kanji = candidate.kanji,
            mode = candidate.mode, reading = candidate.reading, dueMs = due, originalDueMs = candidate.dueMs,
        ),
    )
}

/** Call inside the same transaction that installs a new content snapshot. */
internal suspend fun AppDatabase.cancelUnavailableTransitionChecks(sourceId: String, nowMs: Long) {
    val pending = reviews().pendingTransitionChecks(sourceId)
    if (pending.isEmpty()) return
    val targets = TransitionTargets(content().kanjiLite(sourceId))
    val states = pending.map { it.mode }.distinct().associateWith { mode ->
        reviews().states(sourceId, Stacks.JLPT_STATE, mode).associateBy { it.kanji to it.reading }
    }
    for (reservation in pending) {
        val state = states[reservation.mode]?.get(reservation.kanji to reservation.reading)
        if (state != null && state.phase != "New" &&
            targets.available(reservation.triggerKanji, reservation.kanji, reservation.mode, reservation.reading)
        ) continue
        reviews().restoreTransitionDue(
            sourceId, reservation.kanji, reservation.mode, reservation.reading, reservation.dueMs, reservation.originalDueMs,
        )
        check(reviews().cancelTransitionCheck(reservation.id, nowMs) == 1)
    }
}

private class TransitionTargets(private val cards: List<KanjiEntity>) {
    private val byKanji = cards.associateBy { it.kanji }
    private val readingPools = mutableMapOf<Int, List<ReadingCard>>()

    fun available(trigger: String, kanji: String, mode: String, reading: String): Boolean {
        val triggerLevel = byKanji[trigger]?.let { Stacks.jlptLevel(it.tags.splitSep()) } ?: return false
        val target = byKanji[kanji] ?: return false
        val targetLevel = Stacks.jlptLevel(target.tags.splitSep()) ?: return false
        if (targetLevel <= triggerLevel || kanji !in KanjiTransitionChecks.related(trigger)) return false
        return when (mode) {
            StudyMode.Quiz.name -> reading.isEmpty()
            StudyMode.Draw.name -> reading.isEmpty() && target.strokesJson != null
            StudyMode.KanjiOn.name, StudyMode.KanjiKun.name -> {
                val kind = if (mode == StudyMode.KanjiOn.name) ReadingKind.On else ReadingKind.Kun
                val card = target.toReadingCard()
                if (reading !in card.quizKeys(kind)) return false
                val pool = readingPools.getOrPut(triggerLevel) {
                    val stack = Stacks.jlpt.single { Stacks.jlptLevel(it.id) == triggerLevel }
                    cards.filter { Stacks.includes(stack, it.tags.splitSep()) }.map { it.toReadingCard() }
                }
                KanjiReadingQuiz.question(card, kind, pool, Random(0), reading = reading) != null
            }
            else -> false
        }
    }
}
