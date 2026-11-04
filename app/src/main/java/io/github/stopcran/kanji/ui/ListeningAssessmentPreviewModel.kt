package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.data.ListeningAssessmentPreview
import io.github.stopcran.kanji.core.listening.ListeningAssessmentProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.random.Random

internal class ListeningAssessmentPreviewModel(private val services: Services) : ViewModel() {
    var preview: ListeningAssessmentPreview? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var familiar by mutableStateOf(false)
        private set
    var visual by mutableStateOf(true)
        private set
    var profile by mutableStateOf(ListeningAssessmentProfile.N5)
        private set
    private val seed = Random.nextLong()
    private var loadJob: Job? = null
    private var startJob: Job? = null
    private var active = true

    fun refresh(
        includeFamiliar: Boolean = familiar, visualAccess: Boolean = visual, profile: ListeningAssessmentProfile = this.profile,
    ) {
        if (busy) return
        active = true
        familiar = includeFamiliar && this.profile == profile
        this.profile = profile
        visual = visualAccess
        preview = null
        error = null
        busy = true
        loadJob = viewModelScope.launch {
            try { listeningAction({ error = it }) { preview = services.listeningAssessments.preview(seed, familiar, visual, profile) } }
            finally { busy = false }
        }
    }

    fun start(onStart: (String) -> Unit) {
        val accepted = preview ?: return
        if (busy || !accepted.selection.available) return
        busy = true
        startJob = viewModelScope.launch {
            try {
                listeningAction({ error = it; preview = null }) {
                    require(accepted.profile == profile && accepted.includeFamiliar == familiar && accepted.visualAccess == visual) {
                        "Diagnostic options changed; confirm a fresh preview"
                    }
                    val id = services.listeningAssessments.start(accepted)
                    if (active) onStart(id)
                }
            } finally { busy = false }
        }
    }

    fun leave() { active = false; loadJob?.cancel(); startJob?.cancel() }
}

@Composable
fun ListeningAssessmentPreviewScreen(route: String, onBack: () -> Unit, onStart: (String) -> Unit) {
    val vm = serviceViewModel(route) { ListeningAssessmentPreviewModel(it) }
    ListeningAssessmentPreviewView(vm, onBack, onStart)
}

@Composable
internal fun ListeningAssessmentPreviewView(vm: ListeningAssessmentPreviewModel, onBack: () -> Unit, onStart: (String) -> Unit) {
    LaunchedEffect(vm) { vm.refresh() }
    DisposableEffect(vm) { onDispose { vm.leave() } }
    Page(stringResource(R.string.listening_diagnostic_title), onBack) {
        LevelFilter(ListeningAssessmentProfile.entries, vm.profile, { vm.refresh(profile = it) }, { "N${it.jlpt}" },
            enabled = !vm.busy)
        Text(stringResource(R.string.listening_preview_profile,
            vm.profile.jlpt, vm.profile.scoredCount, vm.profile.groups.size,
            vm.profile.minimumMs / 60_000, vm.profile.maximumMs / 60_000))
        Text(stringResource(R.string.listening_preview_separate_scope))
        Text(stringResource(R.string.listening_preview_no_srs))
        Text(stringResource(R.string.listening_preview_timing_policy))
        Text(stringResource(R.string.listening_preview_interruption_policy))
        FilterChip(selected = vm.familiar, onClick = { vm.refresh(includeFamiliar = !vm.familiar) }, enabled = !vm.busy,
            label = { Text(stringResource(if (vm.profile == ListeningAssessmentProfile.N4)
                R.string.listening_include_familiar_stories else R.string.listening_include_familiar_material)) })
        if (vm.profile == ListeningAssessmentProfile.N4) {
            Text(stringResource(R.string.listening_familiar_policy_n4))
        }
        FilterChip(selected = vm.visual, onClick = { vm.refresh(visualAccess = !vm.visual) }, enabled = !vm.busy,
            label = { Text(stringResource(R.string.listening_picture_access)) })
        Text(stringResource(R.string.listening_picture_help_equivalence))
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let { Text(stringResource(R.string.listening_assessment_preview_error), color = MaterialTheme.colorScheme.error) }
        val preview = vm.preview
        if (preview != null) {
            Text(stringResource(R.string.listening_captured_source,
                preview.sourceId, preview.branch ?: stringResource(R.string.not_synced)), style = MaterialTheme.typography.bodySmall)
            if (preview.branch != null && preview.requestedBranch != preview.branch) {
                Text(stringResource(R.string.listening_configured_branch_snapshot, preview.requestedBranch, preview.branch))
            }
            preview.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.selection.groups.forEach { status ->
                HorizontalDivider()
                Text(status.group.type.label(), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.listening_assessment_group_eligible,
                    status.eligibleScored, status.group.scored, status.eligibleExamples))
                Text(stringResource(R.string.listening_assessment_group_readiness,
                    status.readyScored, status.group.pool, status.unavailable, status.coolingDown,
                    status.familiarExcluded, status.inaccessible))
                status.nextCooldownMs?.let { Text(listeningCooldownLocalizedText(Instant.ofEpochMilli(it))) }
            }
            preview.selection.durationMs?.let {
                Text(stringResource(R.string.listening_projected_duration, it / 60_000, it / 1_000 % 60))
            }
            preview.selection.durationRange?.let {
                Text(stringResource(R.string.listening_all_eligible_duration,
                    it.shortestMs / 60_000, it.shortestMs / 1_000 % 60,
                    it.longestMs / 60_000, it.longestMs / 1_000 % 60))
            }
            val familiarExamples = preview.selection.items.count {
                it.lastExposureMs != null && it.record.task.usage == io.github.stopcran.kanji.core.listening.TaskUsage.Example
            }
            if (preview.selection.items.isNotEmpty()) Text(stringResource(
                R.string.listening_familiar_selected_counts,
                preview.selection.familiarCount,
                preview.profile.scoredCount,
                familiarExamples,
            ))
            if (preview.selection.problems.isNotEmpty()) {
                Text(stringResource(R.string.listening_assessment_availability_problem), color = MaterialTheme.colorScheme.error)
            }
            if (preview.selection.available) {
                Button(onClick = { vm.start(onStart) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.listening_capture_section))
                }
                Text(stringResource(R.string.listening_confirm_source_familiarity))
            } else Text(stringResource(R.string.listening_full_section_unavailable))
        }
        OutlinedButton(onClick = { vm.refresh() }, enabled = !vm.busy) { Text(stringResource(R.string.listening_refresh_availability)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.listening_back_guided)) }
        Text(stringResource(R.string.listening_no_official_score))
    }
}
