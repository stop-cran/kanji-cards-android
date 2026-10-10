package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.reading.KanjiReadingQueue
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.WeekProgress
import io.github.stopcran.kanji.core.srs.Stack
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.Advancement
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordStack
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.forDirection
import io.github.stopcran.kanji.data.inStack
import io.github.stopcran.kanji.data.inWordStack
import io.github.stopcran.kanji.data.levels
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.weekProgress
import io.github.stopcran.kanji.data.stacks
import io.github.stopcran.kanji.data.toReadingCard
import io.github.stopcran.kanji.data.toSrs
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

enum class HomeMode { Meaning, Drawing, Readings, WordJp, WordEn, WordReading }

/** Canonical state of the main screen and the Kanji / Words pages; computed once and consumed as slices so counts never drift. */
class HomeData(
    val loading: Boolean,
    val sourceId: String,
    val kanji: List<KanjiEntity>,
    val stacks: List<Stack>,
    val stack: Stack,
    val wordStacks: List<WordStack>,
    val wordStack: WordStack,
    val wordCount: Int,
    val modes: Map<HomeMode, ModeState>,
    val n4Offer: Boolean,
    val pacing: NewAllowance? = null,
    val week: WeekProgress? = null,
) {
    fun mode(m: HomeMode): ModeState = if (loading) ModeState.Loading else modes[m] ?: ModeState.Loading

    /** "3 due, 5 new" over the given modes, or null while loading / when nothing is offered. */
    fun summary(vararg ms: HomeMode): String? {
        if (loading) return null
        val starts = ms.mapNotNull { mode(it) as? ModeState.Start }
        if (starts.isEmpty()) return if (ms.any { mode(it) == ModeState.PracticeMore }) "All caught up" else "Nothing to study yet"
        val new = starts.sumOf { it.new }.let { n -> pacing?.let { minOf(n, it.remaining) } ?: n }
        return "${starts.sumOf { it.due }} due, $new new"
    }
}

private class Extras(val stackId: String, val wordStackId: String, val allowance: NewAllowance, val week: WeekProgress, val unlocked: Set<String>, val introducedReadings: Set<String>)

/** Null until the first emission, so the screen can tell "still loading" from "nothing yet". */
@Composable
private fun rememberStates(app: KanjiApp, sourceId: String, stack: String, mode: StudyMode): Map<String, SrsState>? {
    val states by remember(sourceId, stack, mode) { app.db.reviews().observeStates(sourceId, stack, mode.name) }.collectAsState(null)
    return remember(states) { states?.associate { it.kanji to it.toSrs() } }
}

@Composable
fun rememberHomeData(app: KanjiApp): HomeData {
    val source by app.settings.source.collectAsState()
    val dailyNew by app.settings.dailyNewCards.collectAsState()
    val weekPlan by app.settings.weekPlan.collectAsState()
    val stackId by app.settings.stack.collectAsState()
    val n4Unlocked by app.settings.n4Unlocked.collectAsState()
    val dismissedMs by app.settings.advanceDismissedMs.collectAsState()
    val wordStackId by app.settings.wordStack.collectAsState()
    val kanjiOrNull by remember(source) { app.db.content().observeKanjiLite(source.id) }.collectAsState(null)
    val wordsOrNull by remember(source) { app.db.content().observeWordsLite(source.id) }.collectAsState(null)
    val kanji = kanjiOrNull ?: emptyList()
    val words = wordsOrNull ?: emptyList()

    val stacks = remember(kanji) { kanji.stacks() }
    val stack = Stacks.find(stackId, stacks)
    val inStack = remember(kanji, stack) { kanji.inStack(stack.id) }
    val wordStacks = WordStacks.offered(n4Unlocked)
    val wordStack = WordStacks.find(wordStackId, wordStacks)
    val levels = remember(kanji) { kanji.levels() }
    val wordsInStack = remember(words, levels, wordStack) { words.inWordStack(wordStack, levels) }

    // A clock tick makes cards that become due, and the day rollover, show up without leaving the page.
    val tick by produceState(Instant.now()) { while (true) { delay(60_000); value = Instant.now() } }
    val today = tick.atZone(ZoneId.systemDefault()).toLocalDate()
    val startOfDay = remember(today) { today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }

    val quiz = rememberStates(app, source.id, stack.id, StudyMode.Quiz)
    val draw = rememberStates(app, source.id, stack.id, StudyMode.Draw)
    val on = rememberStates(app, source.id, stack.id, StudyMode.KanjiOn)
    val kun = rememberStates(app, source.id, stack.id, StudyMode.KanjiKun)
    val jp = rememberStates(app, source.id, wordStack.id, StudyMode.WordJpEn)
    val en = rememberStates(app, source.id, wordStack.id, StudyMode.WordEnJp)
    val rd = rememberStates(app, source.id, wordStack.id, StudyMode.WordReading)

    val statesReady = kanjiOrNull != null && wordsOrNull != null && listOf(quiz, draw, on, kun, jp, en, rd).all { it != null }
    val extras by produceState<Extras?>(null, source.id, stack.id, wordStack.id, startOfDay, dailyNew, weekPlan, statesReady, quiz, draw, on, kun, jp, en, rd) {
        if (!statesReady) return@produceState
        val r = app.db.reviews()
        val allowance = app.db.newAllowance(app.settings, source.id, Instant.now())
        value = Extras(stack.id, wordStack.id, allowance, app.db.weekProgress(app.settings, source.id, Instant.now()), r.meaningLearned(source.id, stack.id).toSet(), r.readingKanjiIntroducedSince(source.id, stack.id, startOfDay).toSet())
    }
    val ex = extras?.takeIf { it.stackId == stack.id && it.wordStackId == wordStack.id }
    val loading = !statesReady || ex == null

    val modes = remember(loading, ex, tick, inStack, wordsInStack, quiz, draw, on, kun, jp, en, rd) {
        if (loading || ex == null) emptyMap() else computeModes(inStack, wordsInStack, ex, tick, quiz!!, draw!!, on!!, kun!!, jp!!, en!!, rd!!)
    }

    val n4Offer = remember(loading, quiz, draw, jp, en, rd, kanji, words, levels, wordStack, n4Unlocked, dismissedMs) {
        if (loading) false else {
            val n5Kanji = inStack.filter { it.jlpt == 5 }
            val n5Words = words.inWordStack(WordStacks.n5, levels)
            val n5Readable = n5Words.forDirection(WordDirection.Reading)
            val onN5 = wordStack.id == WordStacks.n5.id
            val ready = Advancement.ready(
                listOf(
                    n5Kanji.map { quiz!![it.kanji] },
                    n5Kanji.filter { it.strokesJson != null }.map { draw!![it.kanji] },
                    if (onN5) n5Words.map { jp!![it.word] } else emptyList(),
                    if (onN5) n5Words.map { en!![it.word] } else emptyList(),
                    if (onN5) n5Readable.map { rd!![it.word] } else emptyList(),
                ),
                Instant.now(),
            )
            Advancement.shouldOffer(ready, n4Unlocked, dismissedMs, System.currentTimeMillis())
        }
    }
    return HomeData(loading, source.id, kanji, stacks, stack, wordStacks, wordStack, wordsInStack.size, modes, n4Offer, ex?.allowance, ex?.week)
}

/** Counts use strict (noise-free) queues so the numbers are stable; sessions build their own randomised queues. */
private fun computeModes(
    inStack: List<KanjiEntity>, words: List<io.github.stopcran.kanji.data.WordEntity>, ex: Extras, now: Instant,
    quiz: Map<String, SrsState>, draw: Map<String, SrsState>, on: Map<String, SrsState>, kun: Map<String, SrsState>,
    jp: Map<String, SrsState>, en: Map<String, SrsState>, rd: Map<String, SrsState>,
): Map<HomeMode, ModeState> {
    val newLeft = ex.allowance.remaining
    fun plain(ids: List<String>, states: Map<String, SrsState>, min: Int, what: String): ModeState {
        val q = QueueBuilder.build(ids, states, now, newLeft, noise = 0.0)
        return ModeState.of(ids.size >= min, "Needs at least $min $what", q.count { !it.isNew }, q.count { it.isNew }, ids.size >= min, "")
    }
    fun word(d: WordDirection, own: Map<String, SrsState>, other: Map<String, SrsState>, locked: String): ModeState {
        val ids = words.forDirection(d).map { it.word }
        val q = WordQueues.build(d, ids, own, other, now, newLeft, noise = 0.0)
        val extra = WordQueues.build(d, ids, own, other, now, 0, extra = true, noise = 0.0)
        return ModeState.of(ids.size >= 2, "Needs at least 2 words", q.count { !it.isNew }, q.count { it.isNew }, extra.isNotEmpty(), locked)
    }
    val cards = inStack.map { it.toReadingCard() }
    val readingStates = mapOf(ReadingKind.On to on, ReadingKind.Kun to kun)
    fun readings(extra: Boolean) = KanjiReadingQueue.build(cards, readingStates, ex.unlocked, ex.introducedReadings, newLeft + ex.introducedReadings.size, now, extra, noise = 0.0)
    val rq = readings(false)
    return mapOf(
        HomeMode.Meaning to plain(inStack.map { it.kanji }, quiz, 2, "kanji in this stack"),
        HomeMode.Drawing to plain(inStack.filter { it.strokesJson != null }.map { it.kanji }, draw, 1, "kanji with stroke data"),
        HomeMode.Readings to ModeState.of(cards.size >= 2, "Needs at least 2 kanji in this stack", rq.count { !it.isNew }, rq.count { it.isNew }, readings(true).isNotEmpty(), "Unlocks after you answer a kanji's meaning well twice"),
        HomeMode.WordJp to word(WordDirection.JpToEn, jp, en, ""),
        HomeMode.WordEn to word(WordDirection.EnToJp, en, jp, "Unlocks as you learn words"),
        HomeMode.WordReading to word(WordDirection.Reading, rd, jp, "Unlocks as you learn words"),
    )
}
