package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.draw.DrawGate
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
import io.github.stopcran.kanji.data.HomeSnapshot
import io.github.stopcran.kanji.data.forDirection
import io.github.stopcran.kanji.data.inWordStack
import io.github.stopcran.kanji.data.toReadingCard
import java.time.Instant

enum class HomeMode { Meaning, Drawing, Readings, WordJp, WordEn, WordReading, WordType, WordPicture, WordToPicture }

/** The stack pickers' contents: kanji and word stacks with their current selection, and how many words (and how many with a picture) the selected one holds. */
class StackChoices(
    val stacks: List<Stack>,
    val stack: Stack,
    val wordStacks: List<WordStack>,
    val wordStack: WordStack,
    val wordCount: Int,
    val pictureWordCount: Int = 0,
)

/** What the home screen says besides the mode counts: the N4 offer, the new-item allowance, week progress and the focus batch. */
class HomeGuidance(
    val n4Offer: Boolean,
    val pacing: NewAllowance? = null,
    val week: WeekProgress? = null,
    val focus: io.github.stopcran.kanji.data.FocusStatus? = null,
    val wordReadiness: Map<WordDirection, WordReadiness> = emptyMap(),
)

data class WordReadiness(val candidates: Int, val unlocked: Int)

private class HomeModes(
    val states: Map<HomeMode, ModeState> = emptyMap(),
    val wordReadiness: Map<WordDirection, WordReadiness> = emptyMap(),
)

/** Canonical state of the main screen and the Kanji / Words pages; computed once and consumed as slices so counts never drift. */
class HomeData(
    val loading: Boolean,
    val sourceId: String,
    val kanji: List<KanjiEntity>,
    private val choices: StackChoices,
    val modes: Map<HomeMode, ModeState>,
    private val guidance: HomeGuidance,
) {
    companion object {
        fun loading(sourceId: String) = HomeData(
            true, sourceId, emptyList(),
            StackChoices(emptyList(), Stacks.everything, emptyList(), WordStacks.n5, 0),
            emptyMap(), HomeGuidance(false),
        )
    }
    val stacks get() = choices.stacks
    val stack get() = choices.stack
    val wordStacks get() = choices.wordStacks
    val wordStack get() = choices.wordStack
    val wordCount get() = choices.wordCount
    val pictureWordCount get() = choices.pictureWordCount
    val n4Offer get() = guidance.n4Offer
    val pacing get() = guidance.pacing
    val week get() = guidance.week
    val focus get() = guidance.focus
    val wordReadiness get() = guidance.wordReadiness

    fun mode(m: HomeMode): ModeState = if (loading) ModeState.Loading else modes[m] ?: ModeState.Loading

    /** "3 due, 5 new" over the given modes, or null while loading / when nothing is offered. */
    @Composable
    fun summary(vararg ms: HomeMode): String? {
        if (loading) return null
        val starts = ms.mapNotNull { mode(it) as? ModeState.Start }
        if (starts.isEmpty()) return if (ms.any { mode(it) == ModeState.PracticeMore }) "All caught up" else "Nothing to study yet"
        val new = starts.sumOf { it.new }.let { n -> pacing?.let { minOf(n, it.remaining) } ?: n }
        return stringResource(R.string.study_due_new, starts.sumOf { it.due }, new)
    }
}

/** Which kanji have Draw and the readings open, under which rule, how many reading cards started today, and which modes other stacks could pad. */
private class Unlocks(
    val learned: Set<String>, val rule: io.github.stopcran.kanji.core.unlock.UnlockRule, val introducedReadings: Int,
    val padModes: Set<StudyMode>, val wordStates: Map<StudyMode, Map<String, SrsState>>,
)

private class Extras(
    val allowance: NewAllowance, val unlocks: Unlocks,
    val focus: io.github.stopcran.kanji.data.FocusStatus?,
)

/** All seven scheduling-state maps, each non-null; exists so loading is decided once instead of with !! at each use. */
internal data class StateSet(
    val quiz: Map<String, SrsState>, val draw: Map<String, SrsState>, val on: Map<String, SrsState>, val kun: Map<String, SrsState>,
    val jp: Map<String, SrsState>, val en: Map<String, SrsState>, val rd: Map<String, SrsState>,
) {
    companion object {
        fun of(
            quiz: Map<String, SrsState>?, draw: Map<String, SrsState>?, on: Map<String, SrsState>?, kun: Map<String, SrsState>?,
            jp: Map<String, SrsState>?, en: Map<String, SrsState>?, rd: Map<String, SrsState>?,
        ): StateSet? = if (quiz == null || draw == null || on == null || kun == null || jp == null || en == null || rd == null) null else StateSet(quiz, draw, on, kun, jp, en, rd)
    }
}

/** Whether to suggest unlocking N4: enough of the N5 kanji, drawings and words are firm, and the offer was not dismissed recently. */
internal fun shouldOfferN4(
    inStack: List<KanjiEntity>, words: List<io.github.stopcran.kanji.data.WordEntity>, levels: Map<String, Int?>, wordStack: WordStack,
    s: StateSet, n4Unlocked: Boolean, dismissedMs: Long, now: Instant, nowMs: Long,
): Boolean {
    val n5Kanji = inStack.filter { it.jlpt == 5 }
    val n5Words = words.inWordStack(WordStacks.n5, levels)
    val onN5 = wordStack.id == WordStacks.n5.id
    // The word signals come from the selected stack's own states, so judge readiness only on the N5 stack.
    if (!onN5) return false
    val ready = Advancement.ready(
        listOf(
            n5Kanji.map { s.quiz[it.kanji] },
            n5Kanji.filter { it.strokesJson != null }.map { s.draw[it.kanji] },
            n5Words.map { s.jp[it.word] },
            n5Words.map { s.en[it.word] },
            n5Words.forDirection(WordDirection.Reading).map { s.rd[it.word] },
        ),
        now,
    )
    return Advancement.shouldOffer(ready, n4Unlocked, dismissedMs, nowMs)
}
internal fun projectHome(snapshot: HomeSnapshot, now: Instant): HomeData {
    val inventory = snapshot.inventory
    val selection = inventory.selection
    val states = snapshot.states
    val stateSet = StateSet(
        states.getValue(StudyMode.Quiz), states.getValue(StudyMode.Draw),
        states.getValue(StudyMode.KanjiOn), states.getValue(StudyMode.KanjiKun),
        states.getValue(StudyMode.WordJpEn), states.getValue(StudyMode.WordEnJp), states.getValue(StudyMode.WordReading),
    )
    val extras = Extras(
        snapshot.allowance,
        Unlocks(snapshot.learned, selection.study.unlockRule, snapshot.introducedReadings, snapshot.padModes, states), snapshot.focus,
    )
    val projection = computeModes(inventory.inStack, inventory.wordsInStack, extras, now, stateSet)
    val n4Offer = shouldOfferN4(
        inventory.inStack, inventory.words, inventory.levels, inventory.wordStack, stateSet,
        selection.n4Unlocked, selection.dismissedMs, now, now.toEpochMilli(),
    )
    return HomeData(
        false, selection.source.id, inventory.kanji,
        StackChoices(
            inventory.stacks, inventory.stack, inventory.wordStacks, inventory.wordStack,
            inventory.wordsInStack.size, inventory.wordsInStack.count { it.quizImage != null },
        ), projection.states,
        HomeGuidance(n4Offer, snapshot.allowance, snapshot.week, snapshot.focus, projection.wordReadiness),
    )
}

/** Counts use strict (noise-free) queues so the numbers are stable; sessions build their own randomised queues. */
private fun computeModes(
    inStack: List<KanjiEntity>, words: List<io.github.stopcran.kanji.data.WordEntity>, ex: Extras, now: Instant, states: StateSet,
): HomeModes {
    val (quiz, draw, on, kun, jp, en, rd) = states
    val newLeft = ex.allowance.remaining
    val readiness = mutableMapOf<WordDirection, WordReadiness>()
    fun plain(
        ids: List<String>, states: Map<String, SrsState>, min: Int, what: String, locked: String = "", pool: Int = ids.size, pad: Boolean = false,
    ): ModeState {
        val q = QueueBuilder.build(ids, states, now, newLeft, noise = 0.0)
        return ModeState.of(pool >= min, "Needs at least $min $what", q.count { !it.isNew }, q.count { it.isNew }, ids.size >= min || pad, locked)
    }
    fun word(d: WordDirection, own: Map<String, SrsState>, other: Map<String, SrsState>, locked: String): ModeState {
        val ids = words.forDirection(d).map { it.word }
        readiness[d] = WordReadiness(ids.size, WordQueues.eligible(d, ids, own, other).size)
        val q = WordQueues.build(d, ids, own, other, now, newLeft, noise = 0.0)
        val extra = WordQueues.build(d, ids, own, other, now, 0, extra = true, noise = 0.0)
        val offerExtra = extra.isNotEmpty() || d.mode in ex.unlocks.padModes
        val unavailable = if (d == WordDirection.Reading) "Needs at least two words with kanji" else "Needs at least 2 words"
        return ModeState.of(ids.size >= 2, unavailable, q.count { !it.isNew }, q.count { it.isNew }, offerExtra, locked)
    }
    val cards = inStack.map { it.toReadingCard() }
    val readingStates = mapOf(ReadingKind.On to on, ReadingKind.Kun to kun)
    fun readings(extra: Boolean) = KanjiReadingQueue.build(cards, readingStates, ex.unlocks.learned, ex.unlocks.introducedReadings, newLeft + ex.unlocks.introducedReadings, now, extra, noise = 0.0)
    val rq = readings(false)
    val locked = ex.unlocks.rule.hint
    val meaningNew = ex.focus?.let { minOf(newLeft, it.capacity) } ?: newLeft
    val meaning = run {
        val q = QueueBuilder.build(inStack.map { it.kanji }, quiz, now, meaningNew, noise = 0.0)
        ModeState.of(inStack.size >= 2, "Needs at least 2 kanji in this stack", q.count { !it.isNew }, q.count { it.isNew }, inStack.size >= 2, "")
    }
    val modes = mapOf(
        HomeMode.Meaning to meaning,
        HomeMode.Drawing to inStack.filter { it.strokesJson != null }.map { it.kanji }.let { strokeIds ->
            plain(
                DrawGate.eligible(strokeIds, draw, ex.unlocks.learned), draw, 1, "kanji with stroke data", locked,
                pool = strokeIds.size, pad = StudyMode.Draw in ex.unlocks.padModes,
            )
        },
        HomeMode.Readings to ModeState.of(
            cards.size >= 2, "Needs at least 2 kanji in this stack", rq.count { !it.isNew }, rq.count { it.isNew },
            readings(true).isNotEmpty() || StudyMode.KanjiOn in ex.unlocks.padModes || StudyMode.KanjiKun in ex.unlocks.padModes, locked,
        ),
        HomeMode.WordJp to word(WordDirection.JpToEn, jp, en, ""),
        HomeMode.WordEn to word(WordDirection.EnToJp, en, jp, "Unlocks as you learn words"),
        HomeMode.WordReading to word(WordDirection.Reading, rd, jp, "Unlocks as you learn words"),
        HomeMode.WordType to word(WordDirection.Type, ex.unlocks.wordStates.getValue(StudyMode.WordType), en, "Unlocks as you learn English → Japanese"),
        HomeMode.WordPicture to word(WordDirection.Picture, ex.unlocks.wordStates.getValue(StudyMode.WordPicture), jp, "Unlocks as you learn words"),
        HomeMode.WordToPicture to word(WordDirection.WordToPicture, ex.unlocks.wordStates.getValue(StudyMode.WordToPicture), jp, "Unlocks as you learn words"),
    )
    return HomeModes(modes, readiness)
}
