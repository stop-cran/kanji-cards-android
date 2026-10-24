package io.github.stopcran.kanji.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.data.ListeningQueue
import io.github.stopcran.kanji.data.ListeningTaskEntity
import kotlinx.coroutines.launch

@Composable
internal fun ListeningBrowser(services: Services, onBack: () -> Unit, onEncounter: (String) -> Unit) {
    val home by rememberListeningHome(services)
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("all") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun start(id: String, task: Boolean, study: Boolean, extra: Boolean = false, described: Boolean = false) {
        val source = home.overview?.sourceId ?: return
        busy = true
        scope.launch {
            try {
                listeningAction({ error = it }) {
                    val encounter = if (task) services.listeningTasks.start(source, id, study, extra, described)
                    else services.listening.start(source, id, study, extra)
                    onEncounter(encounter)
                }
            } finally { busy = false }
        }
    }
    Page("Listening - N5", onBack, scrollable = home.overview != null && home.queue != null) {
        Text(home.summary())
        Text("English listen-first passages and Japanese task formats share the same daily allowance and exposure cooldown.")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val filters = listOf("all" to "All formats", "legacy" to "English listen-first") +
                ListeningTaskPolicy.supportedTypes.map { it.wire to it.label }
            filters.forEach { (id, label) ->
                FilterChip(selected = filter == id, onClick = { filter = id; error = null }, label = { Text(label) })
            }
        }
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
                Text("Resume or explicitly discard this source's unfinished listening activity before starting another.")
            } else if (home.assessmentPending) {
                Text("An unfinished assessment owns this source. Update the app to resume it.")
            } else {
                val ids = listeningFilteredIds(queue, filter)
                val selected = data.copy(due = queue.due.filter { it in ids }, fresh = queue.fresh.filter { it in ids },
                    extra = queue.extra.filter { it in ids })
                ListeningStartButton(selected, busy) { id, extra -> start(id, queue.tasks.any { it.id == id }, false, extra) }
                if (selected.due.isEmpty() && selected.fresh.isEmpty() && selected.extra.isNotEmpty()) {
                    Text("Practice more is scored extra practice. It may exceed daily plans, but never the 24-hour cooldown.")
                }
                Text("Shared new-item allowance: ${queue.shared.remaining} of ${queue.shared.total} remaining today.")
                Text("Listening time budget: ${queue.allowance.milliseconds / 1_000} estimated seconds remaining today.")
                pacingExplanation(queue.shared)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Budgets and queue order are shared across filters. Due reviews do not use these daily budgets.")
            }
            Text("Study is unscored. It records familiarity and delays a new scored attempt until 24 hours after the latest exposure.")
            home.resumes.filter { it.sourceId != data.sourceId }.forEach { pending ->
                OutlinedButton(onClick = { onEncounter(pending.id) }, enabled = !busy) { Text("Resume captured encounter - ${pending.sourceId}") }
            }
            if (queue.passages.isEmpty() && queue.tasks.isEmpty()) Text("Sync an N5 listening collection in Settings.")
            else if (listeningFilteredIds(queue, filter).isEmpty()) Text("No items in this format yet. Sync an approved collection to add them.")
            if (filter == "all" || filter == "legacy") {
                Text("English listen-first: one passage, then three English questions; one aggregate result.")
                queue.passages.forEach { row ->
                    val record = remember(row.json) { ListeningContent.parse("listening/${row.id}.json", row.json) }
                    HorizontalDivider()
                    Text(record.passage.title, style = MaterialTheme.typography.titleMedium)
                    Text("${record.passage.kind} - ${row.durationMs?.div(1_000)?.let { "${it}s" } ?: "No recording"}")
                    queue.cooldowns[row.id]?.let { Text(listeningCooldownText(it)) }
                    queue.blocked[row.id]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { start(row.id, false, true) }, enabled = row.ready && !occupied && !busy) {
                        Text("Study without scoring")
                    }
                }
            }
            queue.tasks.filter { filter == "all" || it.taskType == filter }.forEach { row ->
                ListeningTaskEntry(row, queue, !occupied && !busy) { described -> start(row.id, true, true, described = described) }
            }
        }
    }
}

internal fun listeningFilteredIds(queue: ListeningQueue, filter: String): Set<String> = buildSet {
    if (filter == "all" || filter == "legacy") addAll(queue.passages.map { it.id })
    addAll(queue.tasks.filter { filter == "all" || it.taskType == filter }.map { it.id })
}

@Composable
private fun ColumnScope.ListeningTaskEntry(row: ListeningTaskEntity, queue: ListeningQueue, available: Boolean, study: (Boolean) -> Unit) {
    val type = ListeningTaskType.entries.find { it.wire == row.taskType }
    val supported = type in ListeningTaskPolicy.supportedTypes
    val picture = remember(row.json) {
        row.json?.let { ListeningTaskContent.parse("listening-tasks/${row.id}.json", it).task.visual != null } ?: false
    }
    HorizontalDivider()
    Text(if (row.json == null) "Unsupported listening task" else row.title, style = MaterialTheme.typography.titleMedium)
    Text(type?.label ?: "Unsupported task type")
    if (row.usage == TaskUsage.Example.name) Text("Worked example - unscored")
    queue.cooldowns[row.id]?.let { Text(listeningCooldownText(it)) }
    if (!row.ready) Text("Complete approved evidence is unavailable. Sync or review the content source.", color = MaterialTheme.colorScheme.error)
    if (!supported) Text("This app cannot present this task type yet.")
    OutlinedButton(onClick = { study(false) }, enabled = row.ready && supported && available) { Text("Study without scoring") }
    if (picture && supported) {
        Text("Picture descriptions are available as unscored study, not equivalent scored nonvisual practice.")
        OutlinedButton(onClick = { study(true) }, enabled = row.ready && available) { Text("Described study - unscored") }
    }
}
