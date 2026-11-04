package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.data.ListeningQueue
import io.github.stopcran.kanji.data.ListeningTaskEntity
import io.github.stopcran.kanji.data.ListeningTaskStart
import kotlinx.coroutines.launch

@Composable
internal fun ListeningBrowser(
    services: Services,
    onBack: () -> Unit,
    onEncounter: (String) -> Unit,
    content: HomeContentState = HomeContentState(true, null),
    onRetry: () -> Unit = {},
) {
    val source by services.settings.source.collectAsState()
    val levels by services.settings.listeningScopes.collectAsState()
    val level = levels[source.id] ?: ListeningScope.N5
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable(source.id) { mutableStateOf("all") }
    val home by rememberListeningHome(services, filter)
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun start(id: String, task: Boolean, study: Boolean, extra: Boolean = false, described: Boolean = false) {
        val source = home.overview?.sourceId ?: return
        val selection = home.queue?.selection ?: return
        busy = true
        scope.launch {
            try {
                listeningAction({ error = it }) {
                    val encounter = if (task) services.listeningTasks.start(source, id, selection, ListeningTaskStart(study, extra, described))
                    else services.listening.start(source, id, study, extra, selection)
                    onEncounter(encounter)
                }
            } finally { busy = false }
        }
    }
    Page(
        stringResource(R.string.listening_title_level, level.label),
        onBack,
        scrollable = content.ready != null && home.overview != null && home.queue != null,
    ) {
        HomeContentNotice(content, onRetry)
        if (content.usable) ListeningHomeSummary(home)
        Text(stringResource(R.string.listening_browser_intro))
        LevelFilter(ListeningScope.entries, level, { services.settings.setListeningScope(source.id, it); error = null }, { it.label },
            description = if (level == ListeningScope.N4)
                stringResource(R.string.level_filter_includes_easier) else null,
            enabled = !busy)
        ListeningFormatFilter(filter, !busy) { filter = it; error = null }
        (error ?: home.error)?.let { Text(stringResource(R.string.listening_generic_error), color = MaterialTheme.colorScheme.error) }
        val data = home.overview
        val queue = home.queue
        if (data != null && queue != null) {
            Text(stringResource(R.string.listening_source, data.sourceId), style = MaterialTheme.typography.bodySmall)
            data.notice?.let { Text(stringResource(R.string.listening_source_warning), color = MaterialTheme.colorScheme.error) }
            if (home.taskNotice != null) Text(stringResource(R.string.listening_task_sync_failed),
                color = MaterialTheme.colorScheme.error)
            val current = home.resumes.firstOrNull { it.sourceId == data.sourceId }
            val occupied = current != null || home.assessmentPending
            if (current != null) {
                Button(
                    onClick = { onEncounter(current.id) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.listening_resume_encounter)) }
                Text(stringResource(R.string.listening_resume_captured_explanation))
            } else if (home.assessmentPending) {
                Text(stringResource(R.string.listening_resume_assessment))
            } else if (content.usable) {
                val selected = data.copy(due = queue.due, fresh = queue.fresh, extra = queue.extra)
                ListeningStartButton(selected, busy) { id, extra -> start(id, queue.tasks.any { it.id == id }, false, extra) }
                if (selected.due.isEmpty() && selected.fresh.isEmpty() && selected.extra.isNotEmpty()) {
                    Text(stringResource(R.string.listening_extra_practice_explanation))
                }
                OutlinedButton(onClick = { onEncounter("listening-assessment-preview:${java.util.UUID.randomUUID()}") },
                    enabled = !occupied && !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.listening_diagnostic_title)) }
                Text(stringResource(R.string.listening_diagnostic_choice))
                Text(stringResource(R.string.content_allowance_remaining, queue.shared.remaining, queue.shared.total))
                Text(stringResource(R.string.listening_time_budget, queue.allowance.milliseconds / 1_000))
                Text(stringResource(R.string.daily_counts_listening))
                pacingExplanation(queue.shared, LocalContext.current)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.listening_budget_shared))
                if (queue.hiddenDueCount > 0) Text(stringResource(R.string.listening_hidden_due, queue.hiddenDueCount))
            }
            Text(stringResource(R.string.listening_study_unscored))
            home.resumes.filter { it.sourceId != data.sourceId }.forEach { pending ->
                OutlinedButton(onClick = { onEncounter(pending.id) }, enabled = !busy) {
                    Text(stringResource(R.string.listening_resume_source, pending.sourceId))
                }
            }
            if (content.usable && queue.passages.isEmpty() && queue.tasks.isEmpty()) {
                Text(stringResource(R.string.listening_no_items))
            }
            if (filter == "all" || filter == "legacy") {
                Text(stringResource(R.string.listening_legacy_intro))
                queue.passages.forEach { row ->
                    val record = remember(row.json) { ListeningContent.parse("listening/${row.id}.json", row.json) }
                    HorizontalDivider()
                    Text(record.passage.title, style = MaterialTheme.typography.titleMedium)
                    val passageKind = when (record.passage.kind) {
                        "dialogue" -> stringResource(R.string.listening_kind_dialogue)
                        "monologue" -> stringResource(R.string.listening_kind_monologue)
                        else -> stringResource(R.string.listening_kind_other)
                    }
                    Text(stringResource(
                        R.string.listening_n5_kind_recording,
                        passageKind,
                        row.durationMs?.div(1_000)?.let { stringResource(R.string.seconds_abbreviated, it) }
                            ?: stringResource(R.string.listening_no_recording),
                    ))
                    queue.cooldowns[row.id]?.let { Text(listeningCooldownLocalizedText(it)) }
                    queue.blocked[row.id]?.let { Text(stringResource(R.string.listening_content_item_unavailable), color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { start(row.id, false, true) }, enabled = row.ready && !occupied && !busy) {
                        Text(stringResource(R.string.listening_study_without_scoring))
                    }
                }
            }
            queue.tasks.forEach { row ->
                ListeningTaskEntry(row, queue, !occupied && !busy) { described -> start(row.id, true, true, described = described) }
            }
        }
    }
}

@Composable
private fun ListeningHomeSummary(home: ListeningHomeState) {
    val data = home.overview ?: return
    val text = when {
        data.active != null || home.resumes.any { it.sourceId == data.sourceId } || home.assessmentPending ->
            stringResource(R.string.listening_unfinished_encounter)
        data.passages.isEmpty() && home.queue?.tasks.orEmpty().isEmpty() ->
            stringResource(R.string.listening_no_recordings)
        else -> {
            val due = home.queue?.due?.size ?: data.due.size
            val fresh = home.queue?.fresh?.size ?: data.fresh.size
            val counts = if (due == 0 && fresh == 0) stringResource(R.string.listening_all_caught_up)
                else stringResource(R.string.listening_due_new_counts, due, fresh)
            val blocked = home.queue?.blocked?.size ?: data.blocked.size
            if (blocked == 0) counts else "$counts, ${stringResource(R.string.listening_content_audio_problems, blocked)}"
        }
    }
    Text(text)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListeningFormatFilter(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val filters = listOf(
            "all" to stringResource(R.string.all_formats),
            "legacy" to stringResource(R.string.listening_legacy_filter),
        ) +
            ListeningTaskPolicy.supportedTypes.map { it.wire to it.label() }
        filters.forEach { (id, label) ->
            FilterChip(selected = selected == id, enabled = enabled, onClick = { onSelect(id) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ColumnScope.ListeningTaskEntry(row: ListeningTaskEntity, queue: ListeningQueue, available: Boolean, study: (Boolean) -> Unit) {
    val type = ListeningTaskType.entries.find { it.wire == row.taskType }
    val supported = type in ListeningTaskPolicy.supportedTypes
    val task = remember(row.json) {
        row.json?.let { ListeningTaskContent.parse("listening-tasks/${row.id}.json", it).task }
    }
    HorizontalDivider()
    Text(if (row.json == null) stringResource(R.string.listening_unsupported_task) else row.title, style = MaterialTheme.typography.titleMedium)
    Text(task?.let { stringResource(R.string.listening_task_level_type, it.jlpt, type?.label() ?: "") }
        ?: stringResource(R.string.listening_unsupported_task_type))
    if (row.usage == TaskUsage.Example.name) Text(stringResource(R.string.listening_worked_example_unscored))
    queue.cooldowns[row.id]?.let { Text(listeningCooldownLocalizedText(it)) }
    if (!row.ready) Text(stringResource(R.string.listening_evidence_unavailable), color = MaterialTheme.colorScheme.error)
    if (!supported) Text(stringResource(R.string.listening_unsupported_task_type_notice))
    OutlinedButton(onClick = { study(false) }, enabled = row.ready && supported && available) { Text(stringResource(R.string.listening_study_without_scoring)) }
    if (task?.visual != null && supported) {
        Text(stringResource(R.string.listening_described_study_explanation))
        OutlinedButton(onClick = { study(true) }, enabled = row.ready && available) { Text(stringResource(R.string.listening_described_study_unscored)) }
    }
}
