package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.LaunchedEffect
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
        Text(stringResource(R.string.studying), style = MaterialTheme.typography.titleMedium)
        val committed = Commitment.of(dailyNew.toIntOrNull() ?: -1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Commitment.entries.forEach { c ->
                androidx.compose.material3.FilterChip(
                    selected = committed == c,
                    onClick = { dailyNew = c.perDay.toString(); app.settings.setDailyNewCards(c.perDay) },
                    label = { Text(stringResource(R.string.label_with_count, c.label, c.perDay)) },
                )
            }
        }
        OutlinedTextField(
            dailyNew,
            { text ->
                dailyNew = text.filter(Char::isDigit).take(3)
                dailyNew.toIntOrNull()?.takeIf { it in 0..200 }?.let { app.settings.setDailyNewCards(it) }
            },
            label = { Text(stringResource(R.string.new_items_per_day_0)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),  modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        val weekPlan by app.settings.weekPlan.collectAsState()
        val week = DayLevel.parseWeek(weekPlan)
        val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        Text(stringResource(R.string.weekly_rhythm_tap_a_day), style = MaterialTheme.typography.bodySmall)
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
            Text(stringResource(R.string.vary_kanji_fonts_as_cards), modifier = Modifier.weight(1f))
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
            Text(stringResource(R.string.daily_study_reminder), modifier = Modifier.weight(1f))
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
            val notificationsBlocked = android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            if (notificationsBlocked) {
                Text(stringResource(R.string.notifications_are_blocked_for_this), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    appContext.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, appContext.packageName)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.open_notification_settings)) }
            }
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
                label = { Text(stringResource(R.string.reminder_hour_0_23)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done),  modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
        }
        Text(
            "Only when cards are due and you have not studied today. If you ignore them, the gaps grow (1, 2, 4, 7, 14 days) and then they stop until you study again.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text(stringResource(R.string.drawing), style = MaterialTheme.typography.titleMedium)
        val brush by app.settings.brush.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrushStyle.entries.forEach { b ->
                androidx.compose.material3.FilterChip(brush == b, { app.settings.setBrush(b) }, label = { Text(b.label, maxLines = 2) }, modifier = Modifier.weight(1f))
            }
        }
        Text(brush.hint, style = MaterialTheme.typography.bodySmall)
        BrushPreview(brush)
        HorizontalDivider()
        Text(stringResource(R.string.advanced), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.content_repository), style = MaterialTheme.typography.titleSmall)
        Text(
            "Cards, words and articles are downloaded from a public GitHub repository, so they can be corrected without an app update. " +
                "The default is the author's repository. Fork it on GitHub, edit the cards and articles in your fork, " +
                "and enter your fork's URL here to study your own version. Each repository keeps its own review progress.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.repository_url)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(branch, { branch = it }, label = { Text(stringResource(R.string.branch)) }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::saveAndSync) { Text(stringResource(R.string.save_sync)) }
            OutlinedButton(onClick = { url = Defaults.CONTENT_REPO_URL; branch = Defaults.CONTENT_BRANCH }) { Text(stringResource(R.string.use_default_repo)) }
        }
        if (status.isNotEmpty()) Text(status)
        HorizontalDivider()
        Text(stringResource(R.string.voice_input), style = MaterialTheme.typography.titleSmall)
        val voiceOn by app.settings.voiceInput.collectAsState()
        val voiceStatus by rememberVoiceStatus()
        when (val v = voiceStatus) {
            VoiceStatus.Checking -> Text(stringResource(R.string.checking_this_device), style = MaterialTheme.typography.bodySmall)
            is VoiceStatus.Unavailable -> Text(stringResource(R.string.not_available_on_this_device, v.reason), style = MaterialTheme.typography.bodySmall)
            VoiceStatus.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.say_the_english_meaning_to), modifier = Modifier.weight(1f))
                Switch(voiceOn, { app.settings.setVoiceInput(it) })
            }
        }
        Text(
            "Adds a microphone button to the meaning quiz and Japanese → English word quiz, so you can answer by speaking one of the shown options. " +
                "Recognition runs only on this device: audio is never sent anywhere or saved, and there is no online fallback. " +
                "The option appears only when the device has an on-device English speech recognizer.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.handwriting_data), style = MaterialTheme.typography.titleSmall)
        val save by app.settings.saveDrawings.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.save_my_drawings_on_this), modifier = Modifier.weight(1f))
            Switch(save, { app.settings.setSaveDrawings(it) })
        }
        Text(
            "Off by default. When on, each checked drawing (strokes, kanji, result) is stored in the app's private storage only. Nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall,
        )
        val context = androidx.compose.ui.platform.LocalContext.current
        var saved by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.stopcran.kanji.data.DrawingLog.count(context) } }
        val scope = rememberCoroutineScope()
        var exportStatus by rememberSaveable { mutableStateOf("") }
        val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                scope.launch {
                    exportStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val n = context.contentResolver.openOutputStream(uri, "wt")?.use { io.github.stopcran.kanji.data.DrawingLog.exportZip(context, it) } ?: error("Cannot open the file")
                            "Exported $n drawings at ${java.time.LocalTime.now().withNano(0)}"
                        }.getOrElse { "Export failed: ${it.message}" }
                    }
                    saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.stopcran.kanji.data.DrawingLog.count(context) }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                exporter.launch("kanji-drawings-$stamp.zip")
            },
            enabled = saved > 0 || exportStatus.isEmpty(),
        ) {
            Text(stringResource(R.string.export_saved_drawings, saved))
        }
        Text(
            "Drawings are also included in Android's automatic backup, but uninstalling the app deletes them, so export a copy first.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (exportStatus.isNotEmpty()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onAbout) { Text(stringResource(R.string.about_sources_and_licences)) }
        Text(source.id + (meta?.let { " — content version ${it.contentVersion}" } ?: " — not synced yet"), style = MaterialTheme.typography.bodySmall)
    }
}
