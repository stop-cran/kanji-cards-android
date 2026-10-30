package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.srs.WeekProgress
import io.github.stopcran.kanji.core.unlock.MeaningUnlock
import io.github.stopcran.kanji.core.unlock.UnlockRule
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

internal data class HomeStudyPreferences(
    val dailyNew: Int, val weekPlan: String, val unlockRule: UnlockRule, val focusEnabled: Boolean, val focusSize: Int,
)

internal data class HomeSelection(
    val source: RepoSource, val stackId: String, val wordStackId: String,
    val n4Unlocked: Boolean, val dismissedMs: Long, val study: HomeStudyPreferences,
)

internal class HomeInventory(val selection: HomeSelection, val kanji: List<KanjiEntity>, val words: List<WordEntity>) {
    val stacks = kanji.stacks()
    val stack = Stacks.find(selection.stackId, stacks)
    val inStack = kanji.inStack(stack.id)
    val levels = kanji.levels()
    val wordStacks = words.offeredStacks(selection.n4Unlocked)
    val wordStack = WordStacks.find(selection.wordStackId, wordStacks)
    val wordsInStack = words.inWordStack(wordStack, levels)
}

internal data class HomeSnapshot(
    val inventory: HomeInventory,
    val states: Map<StudyMode, Map<String, SrsState>>,
    val allowance: NewAllowance,
    val week: WeekProgress,
    val learned: Set<String>,
    val introducedReadings: Int,
    val padModes: Set<StudyMode>,
    val focus: FocusStatus?,
)

internal interface HomeRepository {
    val currentSource: RepoSource
    val selection: Flow<HomeSelection>
    fun ready(source: RepoSource): Flow<Boolean>
    fun observe(selection: HomeSelection, now: Instant, zone: ZoneId): Flow<HomeSnapshot>
}

internal class RoomHomeRepository(private val db: AppDatabase, private val settings: Settings) : HomeRepository {
    override val currentSource get() = settings.source.value
    private val study = combine(
        settings.dailyNewCards, settings.weekPlan, settings.unlockRule, settings.focusEnabled, settings.focusSize,
    ) { daily, week, unlock, focus, size -> HomeStudyPreferences(daily, week, unlock, focus, size) }
    override val selection = combine(
        settings.source, settings.stack, settings.wordStack,
        combine(settings.n4Unlocked, settings.advanceDismissedMs) { unlocked, dismissed -> unlocked to dismissed },
        study,
    ) { source, stack, words, advancement, preferences ->
        HomeSelection(source, stack, words, advancement.first, advancement.second, preferences)
    }

    override fun ready(source: RepoSource) = db.content().observeContentReady(source.id)

    override fun observe(selection: HomeSelection, now: Instant, zone: ZoneId): Flow<HomeSnapshot> =
        db.invalidationTracker.createFlow(
            "kanji", "words", "review_state", "review_log", "listening_encounters", "listening_task_encounters",
        ).map { load(selection, now, zone) }

    private suspend fun load(selection: HomeSelection, now: Instant, zone: ZoneId): HomeSnapshot = db.withTransaction {
        val source = selection.source.id
        val inventory = HomeInventory(selection, db.content().kanjiLite(source), db.content().wordsLite(source))
        val reviews = db.reviews()
        val kanjiModes = listOf(StudyMode.Quiz, StudyMode.Draw, StudyMode.KanjiOn, StudyMode.KanjiKun)
        val states = (kanjiModes + WordDirection.entries.map { it.mode }).associateWith { mode ->
            val stack = if (mode in kanjiModes) inventory.stack.id else inventory.wordStack.id
            val rows = reviews.states(source, stack, mode.name)
            if (mode == StudyMode.KanjiOn || mode == StudyMode.KanjiKun) rows.toReadingStates()
            else rows.associate { it.kanji to it.toSrs() }
        }
        val pad = kanjiModes.filterTo(HashSet()) { mode ->
            val pool = if (mode == StudyMode.Draw) inventory.kanji.filter { it.strokesJson != null } else inventory.kanji
            db.padCandidates(source, inventory.stack.id, mode, pool, inventory.inStack.mapTo(HashSet()) { it.kanji }).isNotEmpty()
        }
        WordDirection.entries.forEach { direction ->
            val ids = inventory.wordsInStack.forDirection(direction).mapTo(HashSet()) { it.word }
            if (db.padWordCandidates(source, inventory.wordStack.id, direction.mode, ids, states.getValue(direction.mode).keys).isNotEmpty()) {
                pad += direction.mode
            }
        }
        val preferences = selection.study
        val focus = if (preferences.focusEnabled) {
            val since = settings.focusSince(source, inventory.stack.id, now.toEpochMilli())
            db.focusStatus(source, inventory.stack.id, inventory.inStack, preferences.focusSize, since)
        } else null
        HomeSnapshot(
            inventory, states,
            db.newAllowance(source, now, zone, preferences.dailyNew, preferences.weekPlan),
            db.weekProgress(source, now, zone, preferences.weekPlan),
            MeaningUnlock.learned(reviews.unlockLog(source, inventory.stack.id), preferences.unlockRule, zone),
            reviews.readingCardsIntroducedSince(source, inventory.stack.id, now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()),
            pad, focus,
        )
    }
}
