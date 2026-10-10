package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import io.github.stopcran.kanji.core.srs.Commitment
import io.github.stopcran.kanji.core.srs.DayLevel
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
        if (!app.settings.setSource(url, branch)) {
            status = "Invalid repository URL (expected https://github.com/<owner>/<repo>) or branch"
            return
        }
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
        Text("Studying", style = MaterialTheme.typography.titleMedium)
        val committed = Commitment.of(dailyNew.toIntOrNull() ?: -1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Commitment.entries.forEach { c ->
                androidx.compose.material3.FilterChip(
                    selected = committed == c,
                    onClick = { dailyNew = c.perDay.toString(); app.settings.setDailyNewCards(c.perDay) },
                    label = { Text("${c.label} (${c.perDay})") },
                )
            }
        }
        OutlinedTextField(
            dailyNew,
            { text ->
                dailyNew = text.filter(Char::isDigit).take(3)
                dailyNew.toIntOrNull()?.takeIf { it in 0..200 }?.let { app.settings.setDailyNewCards(it) }
            },
            label = { Text("New items per day (0-200)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        val weekPlan by app.settings.weekPlan.collectAsState()
        val week = DayLevel.parseWeek(weekPlan)
        val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        Text("Weekly rhythm: tap a day to switch between full, light (half) and rest", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            week.forEachIndexed { i, level ->
                OutlinedButton(
                    onClick = { app.settings.setWeekPlan(DayLevel.encode(week.toMutableList().also { it[i] = level.next() })) },
                    modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dayNames[i], style = MaterialTheme.typography.labelSmall)
                        Text(when (level) { DayLevel.Full -> "Full"; DayLevel.Light -> "Light"; DayLevel.Off -> "Rest" }, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(
            "New items are shared by all quizzes, and pause automatically while many reviews are waiting. Reviews are never limited.",
            style = MaterialTheme.typography.bodySmall,
        )
        val vary by app.settings.varyFonts.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Vary kanji fonts as cards mature", modifier = Modifier.weight(1f))
            Switch(vary, { app.settings.setVaryFonts(it) })
        }
        HorizontalDivider()
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
        HorizontalDivider()
        Text("Drawing", style = MaterialTheme.typography.titleMedium)
        val brush by app.settings.brush.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrushStyle.entries.forEach { b ->
                androidx.compose.material3.FilterChip(brush == b, { app.settings.setBrush(b) }, label = { Text(b.label, maxLines = 2) }, modifier = Modifier.weight(1f))
            }
        }
        Text(brush.hint, style = MaterialTheme.typography.bodySmall)
        BrushPreview(brush)
        HorizontalDivider()
        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        Text("Content repository", style = MaterialTheme.typography.titleSmall)
        Text(
            "Cards, words and articles are downloaded from a public GitHub repository, so they can be corrected without an app update. " +
                "The default is the author's repository. Fork it on GitHub, edit the cards and articles in your fork, " +
                "and enter your fork's URL here to study your own version. Each repository keeps its own review progress.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(url, { url = it }, label = { Text("Repository URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::saveAndSync) { Text("Save & sync") }
            OutlinedButton(onClick = { url = Defaults.CONTENT_REPO_URL; branch = Defaults.CONTENT_BRANCH }) { Text("Use default repo") }
        }
        if (status.isNotEmpty()) Text(status)
        HorizontalDivider()
        Text("Voice input", style = MaterialTheme.typography.titleSmall)
        val voiceOn by app.settings.voiceInput.collectAsState()
        val voiceStatus by rememberVoiceStatus()
        when (val v = voiceStatus) {
            VoiceStatus.Checking -> Text("Checking this device…", style = MaterialTheme.typography.bodySmall)
            is VoiceStatus.Unavailable -> Text("Not available on this device: ${v.reason}.", style = MaterialTheme.typography.bodySmall)
            VoiceStatus.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Say the English meaning to answer", modifier = Modifier.weight(1f))
                Switch(voiceOn, { app.settings.setVoiceInput(it) })
            }
        }
        Text(
            "Adds a microphone button to the meaning quiz and Japanese → English word quiz, so you can answer by speaking one of the shown options. " +
                "Recognition runs only on this device: audio is never sent anywhere or saved, and there is no online fallback. " +
                "The option appears only when the device has an on-device English speech recognizer.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Handwriting data", style = MaterialTheme.typography.titleSmall)
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
                    val n = context.contentResolver.openOutputStream(uri, "wt")?.use { io.github.stopcran.kanji.data.DrawingLog.exportZip(context, it) } ?: error("Cannot open the file")
                    "Exported $n drawings at ${java.time.LocalTime.now().withNano(0)}"
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }
        OutlinedButton(
            onClick = {
                saved = io.github.stopcran.kanji.data.DrawingLog.count(context)
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                exporter.launch("kanji-drawings-$stamp.zip")
            },
            enabled = saved > 0 || exportStatus.isEmpty(),
        ) {
            Text("Export saved drawings ($saved)")
        }
        Text(
            "Drawings are also included in Android's automatic backup, but uninstalling the app deletes them, so export a copy first.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (exportStatus.isNotEmpty()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onAbout) { Text("About, sources and licences") }
        Text(source.id + (meta?.let { " — content version ${it.contentVersion}" } ?: " — not synced yet"), style = MaterialTheme.typography.bodySmall)
    }
}
