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
    Page("Listening - ${level.label}", onBack, scrollable = content.ready != null && home.overview != null && home.queue != null) {
        HomeContentNotice(content, onRetry)
        if (content.usable) Text(home.summary())
        Text("English listen-first passages and Japanese task formats share the same daily allowance and exposure cooldown.")
        LevelFilter(ListeningScope.entries, level, { services.settings.setListeningScope(source.id, it); error = null }, { it.label },
            description = if (level == ListeningScope.N4)
                stringResource(R.string.level_filter_includes_easier) else null,
            enabled = !busy)
        ListeningFormatFilter(filter, !busy) { filter = it; error = null }
        (error ?: home.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val data = home.overview
        val queue = home.queue
        if (data != null && queue != null) {
            Text("Source: ${data.sourceId}", style = MaterialTheme.typography.bodySmall)
            data.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (home.taskNotice != null) Text("Some Japanese task updates failed; the accepted collection is retained. Sync again to retry.",
                color = MaterialTheme.colorScheme.error)
            val current = home.resumes.firstOrNull { it.sourceId == data.sourceId }
            val occupied = current != null || home.assessmentPending
            if (current != null) {
                Button(onClick = { onEncounter(current.id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Resume encounter") }
                Text("Resume uses the captured content, even outside this level/format. Resume or explicitly discard it before starting another.")
            } else if (home.assessmentPending) {
                Text("An unfinished assessment owns this source. Resume it before starting another listening activity.")
            } else if (content.usable) {
                val selected = data.copy(due = queue.due, fresh = queue.fresh, extra = queue.extra)
                ListeningStartButton(selected, busy) { id, extra -> start(id, queue.tasks.any { it.id == id }, false, extra) }
                if (selected.due.isEmpty() && selected.fresh.isEmpty() && selected.extra.isNotEmpty()) {
                    Text("Practice more is scored extra practice. It may exceed daily plans, but never the 24-hour cooldown.")
                }
                OutlinedButton(onClick = { onEncounter("listening-assessment-preview:${java.util.UUID.randomUUID()}") },
                    enabled = !occupied && !busy, modifier = Modifier.fillMaxWidth()) { Text("Japanese diagnostic section") }
                Text("Choose a separate N5 (24-item) or N4 (28-item) diagnostic, each with four examples and no SRS or daily-allowance changes.")
                Text(stringResource(R.string.content_allowance_remaining, queue.shared.remaining, queue.shared.total))
                Text("Listening time budget: ${queue.allowance.milliseconds / 1_000} estimated seconds remaining today.")
                Text(stringResource(R.string.daily_counts_listening))
                pacingExplanation(queue.shared)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Budgets and reservations are shared across all levels and formats. Due backlog, including hidden items, reduces new-listening time.")
                if (queue.hiddenDueCount > 0) Text("${queue.hiddenDueCount} due items outside this selection still affect its new-listening allowance.")
            }
            Text("Study is unscored. It records familiarity and delays a new scored attempt until 24 hours after the latest exposure.")
            home.resumes.filter { it.sourceId != data.sourceId }.forEach { pending ->
                OutlinedButton(onClick = { onEncounter(pending.id) }, enabled = !busy) { Text("Resume captured encounter - ${pending.sourceId}") }
            }
            if (content.usable && queue.passages.isEmpty() && queue.tasks.isEmpty()) {
                Text("No items in this level/format yet. Sync an approved collection in Settings.")
            }
            if (filter == "all" || filter == "legacy") {
                Text("English listen-first: one passage, then three English questions; one aggregate result.")
                queue.passages.forEach { row ->
                    val record = remember(row.json) { ListeningContent.parse("listening/${row.id}.json", row.json) }
                    HorizontalDivider()
                    Text(record.passage.title, style = MaterialTheme.typography.titleMedium)
                    Text("N5 - ${record.passage.kind} - ${row.durationMs?.div(1_000)?.let { "${it}s" } ?: "No recording"}")
                    queue.cooldowns[row.id]?.let { Text(listeningCooldownText(it)) }
                    queue.blocked[row.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { start(row.id, false, true) }, enabled = row.ready && !occupied && !busy) {
                        Text("Study without scoring")
                    }
                }
            }
            queue.tasks.forEach { row ->
                ListeningTaskEntry(row, queue, !occupied && !busy) { described -> start(row.id, true, true, described = described) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListeningFormatFilter(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val filters = listOf("all" to "All formats", "legacy" to "English listen-first") +
            ListeningTaskPolicy.supportedTypes.map { it.wire to it.label }
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
    Text(if (row.json == null) "Unsupported listening task" else row.title, style = MaterialTheme.typography.titleMedium)
    Text(task?.let { "N${it.jlpt} - ${type?.label}" } ?: "Unsupported task type")
    if (row.usage == TaskUsage.Example.name) Text("Worked example - unscored")
    queue.cooldowns[row.id]?.let { Text(listeningCooldownText(it)) }
    if (!row.ready) Text("Complete approved evidence is unavailable. Sync or review the content source.", color = MaterialTheme.colorScheme.error)
    if (!supported) Text("This app cannot present this task type yet.")
    OutlinedButton(onClick = { study(false) }, enabled = row.ready && supported && available) { Text("Study without scoring") }
    if (task?.visual != null && supported) {
        Text("Picture descriptions are available as unscored study, not equivalent scored nonvisual practice.")
        OutlinedButton(onClick = { study(true) }, enabled = row.ready && available) { Text("Described study - unscored") }
    }
}
