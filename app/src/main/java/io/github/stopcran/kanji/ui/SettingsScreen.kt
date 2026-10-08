package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.Defaults
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.data.SyncResult
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: KanjiApp, onBack: () -> Unit, onAbout: () -> Unit) {
    val source by app.settings.source.collectAsState()
    val meta by remember(source) { app.db.content().observeMeta(source.id) }.collectAsState(null)
    var url by rememberSaveable { mutableStateOf(app.settings.repoUrl) }
    var branch by rememberSaveable { mutableStateOf(app.settings.branch) }
    var dailyNew by rememberSaveable { mutableStateOf(app.settings.dailyNewCards.value.toString()) }
    var status by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun saveAndSync() {
        val n = dailyNew.toIntOrNull()
        if (n == null || n !in 0..200) {
            status = "Daily new cards must be a number from 0 to 200"
            return
        }
        if (!app.settings.setSource(url, branch)) {
            status = "Invalid repository URL (expected https://github.com/<owner>/<repo>) or branch"
            return
        }
        app.settings.setDailyNewCards(n)
        status = "Syncing…"
        scope.launch {
            status = when (val r = app.contentSync.sync(app.settings.source.value, force = true)) {
                is SyncResult.Updated -> "Synced ${r.kanji} kanji, ${r.words} words" + if (r.problems.isEmpty()) "" else " (${r.problems.size} files skipped: ${r.problems.first()})"
                SyncResult.UpToDate -> "Up to date"
                is SyncResult.Failed -> "Sync failed: ${r.message}"
            }
        }
    }

    Page("Settings", onBack) {
        Text("Content repository", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cards, words and articles are downloaded from a public GitHub repository, so they can be corrected without an app update. " +
                "The default is the author's repository. Fork it on GitHub, edit the cards and articles in your fork, " +
                "and enter your fork's URL here to study your own version. Each repository keeps its own review progress.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(url, { url = it }, label = { Text("Repository URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(dailyNew, { dailyNew = it.filter(Char::isDigit).take(3) }, label = { Text("New cards per day") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val vary by app.settings.varyFonts.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vary kanji fonts as cards mature", modifier = Modifier.weight(1f))
            Switch(vary, { app.settings.setVaryFonts(it) })
        }
        Text("Brush for drawing", style = MaterialTheme.typography.titleMedium)
        val brush by app.settings.brush.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrushStyle.entries.forEach { b ->
                androidx.compose.material3.FilterChip(brush == b, { app.settings.setBrush(b) }, label = { Text(b.label, maxLines = 2) }, modifier = Modifier.weight(1f))
            }
        }
        Text(brush.hint, style = MaterialTheme.typography.bodySmall)
        BrushPreview(brush)
        val remind by app.settings.reminderEnabled.collectAsState()
        val remindHour by app.settings.reminderHour.collectAsState()
        val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
        fun enableReminder() {
            app.settings.setReminderEnabled(true)
            io.github.stopcran.kanji.data.ReminderWorker.schedule(appContext, app.settings.reminderHour.value, replace = true)
        }
        val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) enableReminder() else status = "Notifications are blocked for this app, so reminders stay off."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily study reminder", modifier = Modifier.weight(1f))
            Switch(remind, { on ->
                if (!on) {
                    app.settings.setReminderEnabled(false)
                    io.github.stopcran.kanji.data.ReminderWorker.cancel(appContext)
                } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    enableReminder()
                }
            })
        }
        if (remind) {
            var hourText by rememberSaveable { mutableStateOf(remindHour.toString()) }
            OutlinedTextField(
                hourText,
                { text ->
                    hourText = text.filter(Char::isDigit).take(2)
                    hourText.toIntOrNull()?.takeIf { it in 0..23 }?.let {
                        app.settings.setReminderHour(it)
                        io.github.stopcran.kanji.data.ReminderWorker.schedule(appContext, it, replace = true)
                    }
                },
                label = { Text("Reminder hour (0-23)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
        }
        Text(
            "Only when cards are due and you have not studied today. If you ignore them, the gaps grow (1, 2, 4, 7, 14 days) and then they stop until you study again.",
            style = MaterialTheme.typography.bodySmall,
        )
        val save by app.settings.saveDrawings.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Save my drawings on this device to help tune handwriting checks", modifier = Modifier.weight(1f))
            Switch(save, { app.settings.setSaveDrawings(it) })
        }
        Text(
            "Off by default. When on, each checked drawing (strokes, kanji, result) is stored in the app's private storage only. Nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
        )
        val context = androidx.compose.ui.platform.LocalContext.current
        var saved by remember { mutableStateOf(io.github.stopcran.kanji.data.DrawingLog.count(context)) }
        var exportStatus by rememberSaveable { mutableStateOf("") }
        val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                exportStatus = runCatching {
                    val n = context.contentResolver.openOutputStream(uri)!!.use { io.github.stopcran.kanji.data.DrawingLog.exportZip(context, it) }
                    "Exported $n drawings"
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }
        OutlinedButton(onClick = { saved = io.github.stopcran.kanji.data.DrawingLog.count(context); exporter.launch("kanji-drawings.zip") }, enabled = saved > 0 || exportStatus.isEmpty()) {
            Text("Export saved drawings ($saved)")
        }
        Text(
            "Drawings are also included in Android's automatic backup, but uninstalling the app deletes them, so export a copy first.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (exportStatus.isNotEmpty()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::saveAndSync) { Text("Save & sync") }
            OutlinedButton(onClick = { url = Defaults.CONTENT_REPO_URL; branch = Defaults.CONTENT_BRANCH }) { Text("Use default repo") }
        }
        if (status.isNotEmpty()) Text(status)
        TextButton(onClick = onAbout) { Text("About, sources and licences") }
        Text(source.id + (meta?.let { " — content version ${it.contentVersion}" } ?: " — not synced yet"), style = MaterialTheme.typography.bodySmall)
    }
}
