package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GRAMMAR_LEVEL_SYNC_NEEDED
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarEvidenceDiagnostic
import io.github.stopcran.kanji.core.grammar.GrammarEvidenceDiagnosticId
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarFocusDecision
import io.github.stopcran.kanji.core.grammar.FocusCandidate
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.GrammarPreferences
import io.github.stopcran.kanji.data.GrammarMessage
import io.github.stopcran.kanji.data.GrammarMessageId
import io.github.stopcran.kanji.data.GrammarPreviewNotice
import io.github.stopcran.kanji.data.GrammarPreviewNoticeId
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.changeGrammarFocus
import io.github.stopcran.kanji.data.changeGrammarGuided
import io.github.stopcran.kanji.data.changeGrammarScope
import io.github.stopcran.kanji.data.grammarStudy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Instant
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R

internal class GrammarHome(
    val sourceId: String,
    val preferences: GrammarPreferences,
    val sets: List<GrammarSet>?,
    val mode: ModeState,
    val focus: GrammarFocusDecision? = null,
    val notices: List<GrammarMessage> = emptyList(),
) {
    @Composable
    fun summary(): String = when {
        sets == null -> stringResource(R.string.loading)
        sets.isEmpty() -> stringResource(R.string.grammar_no_exercises)
        mode is ModeState.Start -> stringResource(R.string.study_due_new, mode.due, mode.new)
        mode == ModeState.PracticeMore -> stringResource(R.string.grammar_all_caught_up)
        mode is ModeState.Locked -> stringResource(R.string.grammar_not_due)
        mode is ModeState.Unavailable && sets.isEmpty() -> stringResource(R.string.grammar_no_exercises_sync)
        mode is ModeState.Unavailable -> stringResource(R.string.grammar_focus_unavailable)
        else -> stringResource(R.string.grammar_nothing_to_study)
    }
}

@Composable
internal fun rememberGrammarHome(app: KanjiApp): State<GrammarHome> {
    val source = app.settings.source.collectAsState().value
    val preferences = app.settings.grammarPreferences.collectAsState().value[source.id] ?: GrammarPreferences()
    val dailyNew = app.settings.dailyNewCards.collectAsState().value
    val weekPlan = app.settings.weekPlan.collectAsState().value
    return key(source.id, preferences, dailyNew, weekPlan) {
        produceState(GrammarHome(source.id, preferences, null, ModeState.Loading)) {
            combine(
                app.db.content().observeMeta(source.id),
                app.db.reviews().observeStates(source.id, GRAMMAR_STACK, "Grammar"),
                app.db.reviews().observeGrammarFocus(source.id),
                app.db.reviews().observeGrammarReadiness(source.id),
                app.db.reviews().observeGrammarReadinessStatus(source.id),
            ) { _, _, _, _, _ -> Unit }.collectLatest {
                val study = app.db.grammarStudy(app.settings, source.id, Instant.now())
                value = GrammarHome(source.id, study.preferences, study.sets, study.mode, study.focus, study.learnerMessages)
            }
        }
    }
}

@Composable
fun GrammarPage(
    app: KanjiApp,
    onBack: () -> Unit,
    onOpenDoc: (String, String, String?) -> Unit,
    onStart: (Boolean) -> Unit,
    onTarget: (GrammarPracticeTarget) -> Unit,
) {
    val source by app.settings.source.collectAsState()
    key(source.id, source.branch) {
        val loaded by rememberGrammarMap(app, source)
        val (actions, message) = rememberGrammarMapActions(app, source, { destination ->
            destination.target()?.let(onTarget)
        }, { path, label -> onOpenDoc(source.id, path, label) })
        Page(stringResource(R.string.home_grammar), onBack, ime = true, scrollable = loaded != GrammarMapState.Loading) {
            when (val state = loaded) {
                GrammarMapState.Loading -> Text(stringResource(R.string.loading))
                is GrammarMapState.Unavailable -> Text(state.message, color = MaterialTheme.colorScheme.error)
                is GrammarMapState.Ready -> {
                    val study = state.snapshot.study
                    val home = GrammarHome(source.id, study.preferences, study.sets, study.mode, study.focus, study.learnerMessages)
                    GrammarStudyControls(app, home, onStart)
                    Text(stringResource(R.string.grammar_browser_title), style = MaterialTheme.typography.titleLarge)
                    GrammarCatalogNotice(state.snapshot.curriculum)
                    message?.let { Text(it) }
                    GrammarMapBrowser(
                        state.snapshot, GrammarMapDestination(source.id, source.branch), actions,
                        app.settings.grammarBrowserFor(source), { app.settings.setGrammarBrowser(source, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GrammarStudyControls(app: KanjiApp, home: GrammarHome, onStart: (Boolean) -> Unit) {
    val preferences = home.preferences
    val coroutineScope = rememberCoroutineScope()
    val count = home.sets?.sumOf { it.exercises.size }
    if (count != null) androidx.compose.ui.res.pluralStringResource(R.plurals.grammar_contrast_exercises, count, count)
        .let { Text(it) }
    else Text(stringResource(R.string.loading))
    Text(stringResource(R.string.grammar_new_scope, grammarScopeIntroduction(preferences.newScope)))
    GrammarScopeChips(preferences.newScope) {
        coroutineScope.launch { app.db.changeGrammarScope(app.settings, home.sourceId, it) }
    }
    Text(
        stringResource(R.string.grammar_new_scope_help),
        style = MaterialTheme.typography.bodySmall,
    )
    if (!preferences.levelsReady && preferences.newScope != GrammarScope.All) Text(localizedModeReason(GRAMMAR_LEVEL_SYNC_NEEDED))
    val unavailableReason = when (home.mode) {
        is ModeState.Locked -> stringResource(R.string.grammar_not_due)
        is ModeState.Unavailable -> stringResource(
            if (home.sets?.isEmpty() == true) R.string.grammar_no_exercises_sync else R.string.grammar_focus_unavailable,
        )
        else -> null
    }
    ModeRow(stringResource(R.string.grammar_choose_or_type), home.mode, localizedReason = unavailableReason, onStart = onStart)
    GrammarFocusControls(app.services, home)
}

@Composable
internal fun GrammarFocusControls(app: Services, home: GrammarHome) {
    val scope = rememberCoroutineScope()
    var choosing by remember(home.sourceId) { mutableStateOf(false) }
    var error by remember(home.sourceId) { mutableStateOf<GrammarMessage?>(null) }
    fun choose(id: String) {
        scope.launch {
            error = null
            try {
                app.db.changeGrammarFocus(app.settings, home.sourceId, id, Instant.now())
                choosing = false
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("GrammarFocus", "Focus change failed", e)
                error = GrammarMessage(GrammarMessageId.FocusChangeFailed)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.grammar_focus_pilot), modifier = Modifier.weight(1f))
        Switch(
            checked = home.preferences.guided,
            onCheckedChange = { enabled ->
                scope.launch { app.db.changeGrammarGuided(app.settings, home.sourceId, enabled) }
            },
        )
    }
    if (!home.preferences.guided) return
    val focus = home.focus
    val current = focus?.current
    if (current != null) GrammarFocusSummary(focus)
    else Text(stringResource(R.string.grammar_focus_guidance), style = MaterialTheme.typography.bodySmall)
    home.notices.forEach { Text(grammarMessageText(it)) }
    if (focus != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choosing = true }) { Text(stringResource(R.string.grammar_focus_change)) }
            OutlinedButton(onClick = { current?.let { choose(it.group.id) } }, enabled = current != null && current.blocked == null) {
                Text(stringResource(if (focus.continuing) R.string.grammar_focus_revisit else R.string.grammar_focus_use))
            }
        }
    }
    error?.let { Text(grammarMessageText(it)) }
    if (choosing && focus != null) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.grammar_focus_choose)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    val alternatives = focus.candidates.filter { !focus.continuing || it.group.id != current?.group?.id }
                    if (alternatives.none { it.blocked == null }) {
                        Text(stringResource(R.string.grammar_focus_no_alternative))
                    }
                    alternatives.forEach { candidate ->
                        TextButton(onClick = { choose(candidate.group.id) }, enabled = candidate.blocked == null) {
                            Text(candidate.group.label)
                        }
                        Text(stringResource(R.string.grammar_recommendation_priority, candidate.score), style = MaterialTheme.typography.bodySmall)
                        if (candidate.blocked != null) {
                            Text(grammarMessageText(GrammarMessage(GrammarMessageId.FocusUnavailable)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    error?.let { Text(grammarMessageText(it)) }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.grammar_focus_close)) } },
        )
    }
}

@Composable
internal fun grammarMessageText(message: GrammarMessage): String = when (message.id) {
    GrammarMessageId.FocusNoEligible -> stringResource(R.string.grammar_focus_reason_no_eligible)
    GrammarMessageId.FocusNoAllowance -> stringResource(R.string.grammar_focus_reason_no_allowance)
    GrammarMessageId.FocusReviewOrChange -> stringResource(R.string.grammar_focus_reason_review_or_change)
    GrammarMessageId.FocusContinue -> stringResource(R.string.grammar_focus_reason_continue, message.label.orEmpty())
    GrammarMessageId.FocusReset -> stringResource(R.string.grammar_focus_reason_reset)
    GrammarMessageId.SyncNeeded -> stringResource(R.string.grammar_sync_prerequisites)
    GrammarMessageId.ContentProblem -> stringResource(R.string.grammar_focus_content_problem)
    GrammarMessageId.ReadinessRebuilding -> stringResource(R.string.grammar_readiness_rebuilding)
    GrammarMessageId.ReadinessProblem -> stringResource(R.string.grammar_readiness_issue)
    GrammarMessageId.NoExercises -> stringResource(R.string.grammar_no_exercises_sync)
    GrammarMessageId.NoDue -> stringResource(R.string.grammar_not_due)
    GrammarMessageId.NoUnseenForScope -> stringResource(
        R.string.grammar_no_unseen_for_scope, grammarScopeIntroduction(message.scope ?: GrammarScope.All),
    )
    GrammarMessageId.TargetUnavailable -> stringResource(R.string.grammar_targeted_practice_unavailable)
    GrammarMessageId.PracticeDataUnavailable -> stringResource(R.string.grammar_practice_data_unavailable)
    GrammarMessageId.FocusChangeFailed -> stringResource(R.string.grammar_focus_change_failed)
    GrammarMessageId.FocusUnavailable -> stringResource(R.string.grammar_focus_unavailable)
}

@Composable
internal fun grammarPreviewNoticeText(notice: GrammarPreviewNotice): String = stringResource(
    when (notice.id) {
        GrammarPreviewNoticeId.Interrupted -> R.string.grammar_practice_interrupted
        GrammarPreviewNoticeId.OverrideConsentExpired -> R.string.grammar_practice_override_expired
        GrammarPreviewNoticeId.PreviewChanged -> R.string.grammar_practice_preview_changed
        GrammarPreviewNoticeId.QueueEmpty -> R.string.grammar_practice_queue_empty
    },
)

@Composable
internal fun grammarEvidenceDiagnosticText(diagnostic: GrammarEvidenceDiagnostic): String = when (diagnostic.id) {
    GrammarEvidenceDiagnosticId.ConflictingEventCopies ->
        stringResource(R.string.grammar_evidence_conflicting_event_copies, requireNotNull(diagnostic.eventId))
    GrammarEvidenceDiagnosticId.FutureDatedEvent ->
        stringResource(R.string.grammar_evidence_future_dated_event, requireNotNull(diagnostic.eventId))
    GrammarEvidenceDiagnosticId.EventCannotBeMapped ->
        stringResource(R.string.grammar_evidence_event_cannot_be_mapped, requireNotNull(diagnostic.eventId))
    GrammarEvidenceDiagnosticId.InconsistentGradeCorrectness ->
        stringResource(R.string.grammar_evidence_inconsistent_grade_correctness, requireNotNull(diagnostic.eventId))
    GrammarEvidenceDiagnosticId.FutureDatedHistory ->
        stringResource(R.string.grammar_evidence_future_dated_history)
}

@Composable
internal fun GrammarFocusSummary(focus: GrammarFocusDecision, activeSession: Boolean = false) {
    val current = focus.current ?: return
    var details by rememberSaveable(current.group.id, activeSession) { mutableStateOf(false) }
    Text(stringResource(
        if (activeSession || focus.continuing) R.string.grammar_focus_current else R.string.grammar_focus_next,
        current.group.label,
    ), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.grammar_focus_guidance), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { details = !details }) {
        Text(stringResource(if (details) R.string.grammar_focus_hide_details else R.string.grammar_focus_show_details))
    }
    if (!details) return
    if (activeSession) Text(stringResource(R.string.grammar_focus_snapshot), style = MaterialTheme.typography.bodySmall)
    Text(localizedGrammarFocusReason(focus), style = MaterialTheme.typography.bodySmall)
    GrammarRecommendationReadout(current)
    current.group.facets.forEach { facet ->
        val evidence = current.evidence.facets.getValue(facet.id)
        val introduced = focus.activation?.introduced.orEmpty().count { it.facetId == facet.id }
        Text(
            stringResource(
                R.string.grammar_focus_facet_evidence,
                facet.label, introduced, evidence.startedKeys, evidence.positiveKeys, evidence.typedPositives,
                evidence.spanMs / 3_600_000.0,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        when {
            !current.group.authored -> stringResource(R.string.grammar_focus_legacy)
            current.evidence.familiar -> stringResource(R.string.grammar_familiarity_not_mastery)
            else -> stringResource(R.string.grammar_familiarity_not_supported)
        },
    )
    current.evidence.diagnostics.forEach { Text(grammarEvidenceDiagnosticText(it), style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun localizedGrammarFocusReason(focus: GrammarFocusDecision): String {
    val current = focus.current
    val activation = focus.activation
    if (current == null || activation == null) return stringResource(R.string.grammar_focus_reason_no_eligible)
    if (focus.continuing) {
        return when {
            focus.selected.isNotEmpty() -> stringResource(R.string.grammar_focus_reason_continue, current.group.label)
            focus.newItemAllowanceExhausted -> stringResource(R.string.grammar_focus_reason_no_allowance)
            else -> stringResource(R.string.grammar_focus_reason_review_or_change)
        }
    }
    val exploration = activation.seed % 3L == 0L
    val policy = stringResource(if (exploration) R.string.grammar_focus_policy_exploration else R.string.grammar_focus_policy_authored)
    val rule = when {
        !exploration -> R.string.grammar_focus_rule_max_supported
        current.score == 0 -> R.string.grammar_focus_rule_score_zero
        else -> R.string.grammar_focus_rule_no_score_zero
    }
    return stringResource(
        R.string.grammar_focus_reason_selection,
        policy,
        activation.seed,
        current.group.label,
        stringResource(rule),
        current.score,
    )
}

@Composable
internal fun GrammarRecommendationReadout(candidate: FocusCandidate) {
    Text(
        stringResource(R.string.grammar_group_recommendation, candidate.group.id, candidate.score),
        style = MaterialTheme.typography.bodySmall,
    )
    if (candidate.recommendations.isEmpty()) {
        Text(stringResource(R.string.grammar_no_recommendation), style = MaterialTheme.typography.bodySmall)
    }
    candidate.recommendations.filter { it.link.importance == candidate.score }.sortedBy { it.sourceGroup.id }.forEach { support ->
        Text(
            stringResource(
                R.string.grammar_recommendation_from,
                support.sourceGroup.label, support.sourceGroup.id, support.link.importance, support.link.reason,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        support.sourceGroup.facets.forEach { facet ->
            val evidence = support.evidence.facets.getValue(facet.id)
            Text(
                stringResource(
                    R.string.grammar_supported_source_facet,
                    facet.id, evidence.positiveKeys, evidence.typedPositives, evidence.spanMs / 3_600_000.0,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun GrammarScopeChips(selected: GrammarScope, onSelect: (GrammarScope) -> Unit) {
    val context = LocalContext.current
    val levels = GrammarScope.entries.filter { it != GrammarScope.All && it != GrammarScope.Unclassified }
    LevelFilter(levels + listOf(GrammarScope.All, GrammarScope.Unclassified), selected, onSelect, { context.getString(grammarScopeLabel(it)) })
}

@Composable
internal fun grammarScopeIntroduction(scope: GrammarScope): String = when (scope) {
    GrammarScope.All -> stringResource(R.string.grammar_scope_all)
    GrammarScope.Unclassified -> stringResource(R.string.grammar_scope_unclassified)
    else -> stringResource(R.string.grammar_scope_and_easier, stringResource(grammarJlptLabel(scope)))
}

@Composable
internal fun grammarScopeBrowse(scope: GrammarScope): String = when (scope) {
    GrammarScope.All -> stringResource(R.string.grammar_scope_all)
    GrammarScope.Unclassified -> stringResource(R.string.grammar_scope_unclassified)
    else -> stringResource(R.string.grammar_scope_only, stringResource(grammarJlptLabel(scope)))
}

internal fun grammarScopeLabel(scope: GrammarScope): Int = when (scope) {
    GrammarScope.All -> R.string.grammar_scope_all
    GrammarScope.Unclassified -> R.string.grammar_scope_unclassified
    GrammarScope.N5 -> R.string.level_n5
    GrammarScope.N4 -> R.string.level_n4
    GrammarScope.N3 -> R.string.level_n3
    GrammarScope.N2 -> R.string.level_n2
    GrammarScope.N1 -> R.string.level_n1
}

private fun grammarJlptLabel(scope: GrammarScope): Int = when (scope) {
    GrammarScope.N5 -> R.string.level_n5
    GrammarScope.N4 -> R.string.level_n4
    GrammarScope.N3 -> R.string.level_n3
    GrammarScope.N2 -> R.string.level_n2
    GrammarScope.N1 -> R.string.level_n1
    else -> R.string.grammar_scope_all
}
