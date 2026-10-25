package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopcran.kanji.Services
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
    Page("Japanese diagnostic section", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListeningAssessmentProfile.entries.forEach { choice ->
                FilterChip(selected = vm.profile == choice, onClick = { vm.refresh(profile = choice) }, enabled = !vm.busy,
                    label = { Text("N${choice.jlpt}") })
            }
        }
        Text("N${vm.profile.jlpt}: ${vm.profile.scoredCount} scored items and ${vm.profile.groups.size} separate worked examples; " +
            "${vm.profile.minimumMs / 60_000}-${vm.profile.maximumMs / 60_000} minutes.")
        Text("This exact diagnostic level is separate from the cumulative guided-practice level.")
        Text("Diagnostic only: no SRS changes, introductions or daily-allowance use. Actual exposure still creates a 24-hour cooldown.")
        Text("Single playback; fixed read, listen and answer windows. Answers may change before the deadline. Skip locks an unanswered " +
            "response but does not advance early. Scored feedback waits until the end.")
        Text("Pause, leaving this screen, loss of focus, headphones, calls, recovery or help irreversibly changes the section to " +
            "interrupted practice. It never restarts automatically.")
        FilterChip(selected = vm.familiar, onClick = { vm.refresh(includeFamiliar = !vm.familiar) }, enabled = !vm.busy,
            label = { Text(if (vm.profile == ListeningAssessmentProfile.N4) "Include familiar scored stories" else "Include familiar material") })
        if (vm.profile == ListeningAssessmentProfile.N4) {
            Text("Scored stories are unseen by default. Worked examples may recur after their 24-hour cooldown; their familiarity is disclosed separately.")
        }
        FilterChip(selected = vm.visual, onClick = { vm.refresh(visualAccess = !vm.visual) }, enabled = !vm.busy,
            label = { Text("I can use the required pictures") })
        Text("Described study remains available in guided Listening; it is not an equivalent nonvisual assessment.")
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val preview = vm.preview
        if (preview != null) {
            Text("Captured source: ${preview.sourceId} (${preview.branch ?: "not synced"})", style = MaterialTheme.typography.bodySmall)
            if (preview.branch != null && preview.requestedBranch != preview.branch) {
                Text("Configured branch: ${preview.requestedBranch}. This preview uses the last accepted ${preview.branch} snapshot.")
            }
            preview.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            preview.selection.groups.forEach { status ->
                HorizontalDivider()
                Text(status.group.type.label, style = MaterialTheme.typography.titleMedium)
                Text("${status.eligibleScored}/${status.group.scored} eligible scored items; ${status.eligibleExamples} eligible example(s).")
                Text("Ready scored pool: ${status.readyScored}/${status.group.pool}. Unavailable: ${status.unavailable}; " +
                    "cooldown: ${status.coolingDown}; familiar excluded: ${status.familiarExcluded}; inaccessible: ${status.inaccessible}.")
                status.nextCooldownMs?.let { Text(listeningCooldownText(Instant.ofEpochMilli(it))) }
            }
            preview.selection.durationMs?.let { Text("Projected duration: ${it / 60_000}m ${it / 1_000 % 60}s") }
            preview.selection.durationRange?.let {
                Text("All eligible forms: ${it.shortestMs / 60_000}m ${it.shortestMs / 1_000 % 60}s - " +
                    "${it.longestMs / 60_000}m ${it.longestMs / 1_000 % 60}s.")
            }
            val familiarExamples = preview.selection.items.count {
                it.lastExposureMs != null && it.record.task.usage == io.github.stopcran.kanji.core.listening.TaskUsage.Example
            }
            if (preview.selection.items.isNotEmpty()) Text(
                "${preview.selection.familiarCount} of ${preview.profile.scoredCount} selected scored items are familiar; " +
                    "$familiarExamples selected examples are familiar.")
            preview.selection.problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            if (preview.selection.available) {
                Button(onClick = { vm.start(onStart) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Capture this section") }
                Text("Confirm the source and familiarity above. The next page has an explicit Begin button.")
            } else Text("A full section is unavailable. No shorter exam or repeated story will be substituted.")
        }
        OutlinedButton(onClick = { vm.refresh() }, enabled = !vm.busy) { Text("Refresh availability") }
        TextButton(onClick = onBack) { Text("Back to guided Listening") }
        Text("No official JLPT scaled score, pass prediction or proficiency certification.")
    }
}
