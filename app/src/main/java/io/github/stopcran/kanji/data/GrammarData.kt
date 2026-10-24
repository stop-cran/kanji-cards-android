package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.GrammarCurriculum
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.GrammarRequirements
import io.github.stopcran.kanji.core.grammar.GrammarAvailability
import io.github.stopcran.kanji.core.grammar.GrammarGroupAvailability
import androidx.room.withTransaction
import io.github.stopcran.kanji.core.grammar.GrammarCursor
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.grammar.GrammarExposure
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarFocusDecision
import io.github.stopcran.kanji.core.grammar.GrammarFocusInput
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GRAMMAR_NOT_DUE
import io.github.stopcran.kanji.core.grammar.GrammarEligibility
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import kotlinx.serialization.json.Json
import java.time.Instant

const val GRAMMAR_STACK = "grammar"
const val GRAMMAR_LEVEL_EPOCH = 1
const val GRAMMAR_GROUP_EPOCH = 2

private val grammarJson = Json { ignoreUnknownKeys = true }

suspend fun AppDatabase.grammarSets(sourceId: String): List<GrammarSet> =
    content().grammar(sourceId).map {
        grammarJson.decodeFromString(GrammarSet.serializer(), it.json)
    }

suspend fun AppDatabase.grammarStates(sourceId: String): Map<String, SrsState> =
    reviews().states(sourceId, GRAMMAR_STACK, StudyMode.Grammar.name).associate { it.kanji to it.toSrs() }

/** Read navigation and its group references from the same accepted snapshot, without touching progress or focus. */
suspend fun AppDatabase.grammarCurriculum(settings: Settings, sourceId: String): GrammarCurriculumState = withTransaction {
    if (settings.grammarFor(sourceId).parserEpoch < GrammarCurriculum.PARSER_EPOCH) {
        return@withTransaction GrammarCurriculumState.PendingRefresh
    }
    val meta = content().meta(sourceId) ?: return@withTransaction GrammarCurriculumState.PendingRefresh
    meta.grammarCurriculumProblem?.let { return@withTransaction GrammarCurriculumState.Invalid(it) }
    val text = meta.grammarCurriculumJson ?: return@withTransaction GrammarCurriculumState.Absent
    try {
        GrammarCurriculum.decode(text, grammarSets(sourceId))
    } catch (e: IllegalArgumentException) {
        GrammarCurriculumState.Invalid("Stored Grammar roadmap is invalid: ${e.message}")
    }
}

data class GrammarFocusTicket(
    val revision: Long,
    val scope: GrammarScope,
    val groupId: String,
    val selected: List<GrammarExposure>,
)

data class GrammarStudy(
    val sets: List<GrammarSet>,
    val states: Map<String, SrsState>,
    val preferences: GrammarPreferences,
    val ordinaryIds: List<String>,
    val extraIds: List<String>,
    val budget: Int,
    val now: Instant,
    val notice: String?,
    val focus: GrammarFocusDecision? = null,
    val ticket: GrammarFocusTicket? = null,
    val readiness: GrammarReadinessSnapshot = GrammarReadinessSnapshot(emptySet(), emptySet(), true, null),
    val availability: Map<String, GrammarGroupAvailability> = emptyMap(),
) {
    val mode: ModeState get() {
        val queue = QueueBuilder.build(ordinaryIds, states, now, budget, noise = 0.0)
        val extra = QueueBuilder.extra(extraIds, states, now, limit = 1, noise = 0.0)
        return ModeState.of(
            sets.any { it.exercises.isNotEmpty() }, "No grammar exercises yet. Sync the content first (settings).",
            queue.count { !it.isNew }, queue.count { it.isNew }, extra.isNotEmpty(), notice ?: GRAMMAR_NOT_DUE,
        )
    }
}

private data class CursorRead(val cursor: GrammarCursor?, val error: String?)

private fun readCursor(row: GrammarFocusEntity?): CursorRead = try {
    require(row == null || row.revision >= 0) { "Invalid focus revision" }
    val cursor = row?.let { Json.decodeFromString(GrammarCursor.serializer(), it.json) } ?: GrammarCursor()
    cursor.validate()
    CursorRead(cursor, null)
} catch (e: IllegalArgumentException) {
    CursorRead(null, "Stored Grammar focus is invalid: ${e.message}")
}

private suspend fun AppDatabase.storeCursor(sourceId: String, old: GrammarFocusEntity?, cursor: GrammarCursor): Long {
    cursor.validate()
    val revision = Math.addExact(old?.revision ?: 0, 1)
    reviews().putGrammarFocus(GrammarFocusEntity(sourceId, revision, Json.encodeToString(GrammarCursor.serializer(), cursor)))
    return revision
}

private fun grammarReadinessNotice(readiness: GrammarReadinessSnapshot, sets: List<GrammarSet>): String? = when {
    readiness.problem != null -> readiness.problem
    readiness.rebuilding && sets.any { it.introductionGroups.isNotEmpty() } ->
        "Rebuilding prerequisite readiness from retained history. Existing readiness and started reviews remain available."
    else -> null
}

/** A consistent read-only preview, or the same calculation with an explicit session reservation. */
suspend fun AppDatabase.grammarStudy(
    settings: Settings,
    sourceId: String,
    now: Instant,
    reserve: Boolean = false,
    extra: Boolean = false,
): GrammarStudy = withTransaction {
    val preferences = settings.grammarFor(sourceId)
    val sets = grammarSets(sourceId)
    val states = grammarStates(sourceId)
    val budget = newAllowance(settings, sourceId, now).remaining
    val pool = GrammarEligibility.pool(sets.flatMap { it.exercises }, states, preferences.newScope, preferences.levelsReady)
    val readiness = grammarReadiness(sourceId, sets)
    val metadataError = when {
        preferences.parserEpoch < GrammarRequirements.PARSER_EPOCH -> "Sync needed for prerequisite-aware introductions (settings)."
        else -> content().meta(sourceId)?.grammarProblem?.let { "Guided introductions blocked by content errors: ${it.take(600)}" }
    }
    val availability = GrammarAvailability.evaluate(
        GrammarFocus.groups(sets), sets.flatMap { it.exercises }.associateBy { it.id }, states, preferences.newScope,
        preferences.levelsReady, readiness.readyGroups, budget, now, newMetadataProblem = metadataError,
    )
    val readinessNotice = grammarReadinessNotice(readiness, sets)
    val base = GrammarStudy(
        sets, states, preferences, pool.ids, pool.ids, budget, now,
        listOfNotNull(pool.newItemMessage, readinessNotice).takeIf { it.isNotEmpty() }?.joinToString("\n"),
        readiness = readiness, availability = availability,
    )
    if (!preferences.guided || extra) return@withTransaction base
    val started = pool.ids.filter { states[it]?.phase?.let { phase -> phase != io.github.stopcran.kanji.core.srs.CardPhase.New } == true }
    if (metadataError != null) return@withTransaction base.copy(ordinaryIds = started, notice = metadataError)
    val row = reviews().grammarFocus(sourceId)
    val loaded = readCursor(row)
    val cursor = loaded.cursor ?: return@withTransaction base.copy(ordinaryIds = started, notice = loaded.error)
    val input = focusInput(sourceId, base, cursor)
    val decision = GrammarFocus.decide(input)
    var ticket: GrammarFocusTicket? = null
    if (reserve && decision.selected.isNotEmpty()) {
        val reserved = cursor.copy(selectionNonce = Math.addExact(cursor.selectionNonce, 1), active = decision.activation)
        val revision = storeCursor(sourceId, row, reserved)
        ticket = GrammarFocusTicket(revision, preferences.newScope, requireNotNull(decision.activation).groupId, decision.selected)
    } else if (reserve && cursor.active != null && decision.activation != cursor.active) {
        storeCursor(sourceId, row, cursor.copy(active = null))
    }
    base.copy(
        ordinaryIds = started + decision.selected.map { it.exerciseId },
        notice = listOfNotNull(readinessNotice, decision.resetReason, decision.reason).joinToString("\n"),
        focus = decision, ticket = ticket,
        availability = GrammarAvailability.evaluate(
            GrammarFocus.groups(sets), sets.flatMap { it.exercises }.associateBy { it.id }, states, preferences.newScope,
            preferences.levelsReady, readiness.readyGroups, budget, now, decision.activation, metadataError,
        ),
    )
}

private suspend fun AppDatabase.focusInput(sourceId: String, study: GrammarStudy, cursor: GrammarCursor) = GrammarFocusInput(
    sourceId, study.sets, study.states, reviews().grammarEvidence(sourceId, study.now.minus(GrammarEvidence.WINDOW).toEpochMilli()),
    study.preferences.newScope, study.budget, study.now, cursor, study.readiness.readyGroups,
)

suspend fun AppDatabase.changeGrammarScope(settings: Settings, sourceId: String, scope: GrammarScope) = withTransaction {
    content().invalidateGrammarFocus(sourceId)
    settings.setGrammarNewScope(sourceId, scope)
}

suspend fun AppDatabase.changeGrammarGuided(settings: Settings, sourceId: String, guided: Boolean) = withTransaction {
    val row = reviews().grammarFocus(sourceId)
    val loaded = readCursor(row)
    if (loaded.cursor != null) {
        if (row != null) storeCursor(sourceId, row, loaded.cursor.copy(active = null))
    } else {
        content().invalidateGrammarFocus(sourceId)
        android.util.Log.e("GrammarFocus", requireNotNull(loaded.error))
    }
    settings.setGrammarGuided(sourceId, guided)
}

suspend fun AppDatabase.changeGrammarFocus(settings: Settings, sourceId: String, groupId: String, now: Instant) = withTransaction {
    val study = grammarStudy(settings, sourceId, now)
    require(study.preferences.guided && study.focus != null) { study.notice ?: "Enable focused introductions first." }
    val row = reviews().grammarFocus(sourceId)
    val loaded = readCursor(row)
    val cursor = requireNotNull(loaded.cursor) { requireNotNull(loaded.error) }
    storeCursor(sourceId, row, GrammarFocus.manual(focusInput(sourceId, study, cursor), groupId))
}

/** The event and its first-introduction quota/ordinal update commit together. */
@Suppress("LongParameterList")
suspend fun AppDatabase.recordGrammarAnswer(
    settings: Settings,
    state: ReviewStateEntity,
    log: ReviewLogEntity,
    response: GrammarResponseEntity,
    ticket: GrammarFocusTicket?,
    now: Instant = Instant.now(),
): String? = withTransaction {
    val first = !reviews().grammarStarted(state.sourceId, state.kanji)
    reviews().recordGrammar(state, log, response)
    attainGrammarReadiness(state.sourceId, now, log.atMs)
    if (!first || ticket == null) return@withTransaction null
    val preferences = settings.grammarFor(state.sourceId)
    if (!preferences.guided || preferences.newScope != ticket.scope) return@withTransaction null
    val row = reviews().grammarFocus(state.sourceId)
    if (row == null || row.revision != ticket.revision) return@withTransaction null
    val loaded = readCursor(row)
    val cursor = loaded.cursor ?: return@withTransaction loaded.error.also { android.util.Log.e("GrammarFocus", requireNotNull(it)) }
    if (cursor.active?.groupId != ticket.groupId) return@withTransaction null
    val exposure = ticket.selected.singleOrNull { it.exerciseId == state.kanji } ?: return@withTransaction null
    val updated = GrammarFocus.recorded(cursor, exposure)
    // Keep the reservation revision: other questions from this same session still own its token.
    reviews().putGrammarFocus(row.copy(json = Json.encodeToString(GrammarCursor.serializer(), updated)))
    null
}
