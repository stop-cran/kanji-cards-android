package io.github.stopcran.kanji.ui

import androidx.compose.foundation.layout.Arrangement
import io.github.stopcran.kanji.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.srs.Commitment
import io.github.stopcran.kanji.core.srs.DayLevel
import io.github.stopcran.kanji.data.AppLanguage
import io.github.stopcran.kanji.data.AppLocale
import io.github.stopcran.kanji.data.LanguageSources
import io.github.stopcran.kanji.data.SourceCandidateFailure
import io.github.stopcran.kanji.data.SourceCandidateResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Suppress("TooGenericExceptionCaught")
@Composable
internal fun SettingsScreen(
    app: KanjiApp, onBack: () -> Unit, onAbout: () -> Unit,
    sync: io.github.stopcran.kanji.data.SyncCoordinator = app.syncCoordinator,
) {
    val source by app.settings.source.collectAsState()
    val meta by remember(source) { app.db.content().observeMeta(source.id) }.collectAsState(null)
    val appLanguage by app.settings.appLanguage.collectAsState()
    val acceptedLanguage = meta?.baseLanguage ?: app.settings.sync.baseLanguage(source.id)
    val interfaceLanguage = AppLocale.resolvedLanguage(appLanguage)
    val customLanguageMismatch = !LanguageSources.isDefault(source) &&
        acceptedLanguage != null && acceptedLanguage != interfaceLanguage
    val pendingCandidate by app.settings.pendingSourceCandidate.collectAsState()
    val activities by sync.activity.collectAsState()
    val activityRevisions by sync.activityRevisions.collectAsState()
    val activity = activities[source]
    val sourceKey = "${source.id}@${source.branch}"
    val sourceActivityRevision = activityRevisions[source] ?: 0L
    var url by rememberSaveable { mutableStateOf(app.settings.repoUrl) }
    var branch by rememberSaveable { mutableStateOf(app.settings.branch) }
    var dailyNew by rememberSaveable { mutableStateOf(app.settings.dailyNewCards.value.toString()) }
    var status by rememberSaveable(stateSaver = SyncStatus.Saver) { mutableStateOf(SyncStatus()) }
    var sourceError by rememberSaveable { mutableStateOf<String?>(null) }
    var statusSource by rememberSaveable { mutableStateOf(sourceKey) }
    var candidateStatusActivityRevision by rememberSaveable { mutableStateOf(sourceActivityRevision) }
    var candidateStatusActivityEpoch by rememberSaveable { mutableStateOf(sync.activityEpoch) }
    var languageConfirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var candidateJob by remember { mutableStateOf<Job?>(null) }
    var candidateRunning by remember { mutableStateOf(false) }
    val candidateRuns = remember { CandidateRunOwnership() }
    val visibleStatusKey = pendingCandidate?.let { "${it.source.id}@${it.source.branch}" } ?: sourceKey
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val displayedStatus = sourceError?.let { SyncStatus(it) } ?: if (pendingCandidate != null) {
        status.takeIf { statusSource == visibleStatusKey } ?: SyncStatus()
    } else displayedSettingsSyncStatus(
        status,
        SettingsStatusProvenance(
            statusSource,
            sourceKey,
            candidateStatusActivityRevision,
            sourceActivityRevision,
            candidateStatusActivityEpoch == sync.activityEpoch,
        ),
        SyncStatus.fromActivity(activity, context),
    )
    val syncing = activity == io.github.stopcran.kanji.data.SyncActivity.Running

    suspend fun cancelCandidate() {
        val pending = app.settings.pendingSourceCandidate.value
        candidateRuns.cancel()
        candidateJob?.cancel()
        candidateJob = null
        candidateRunning = false
        pending?.let { app.contentSync.cancelSourceCandidate(app.settings, it.token) }
        status = status.withCurrentLocaleCandidateMessage(context)
        if (status.candidateMessage == CandidateMessage.Checking) {
            status = SyncStatus()
            statusSource = sourceKey
            candidateStatusActivityRevision = -1L
            candidateStatusActivityEpoch = sync.activityEpoch
        }
    }

    fun candidateFailure(failure: SourceCandidateFailure): CandidateMessage =
        when (failure) {
            SourceCandidateFailure.UpdatesUnavailable -> CandidateMessage.Unavailable
            SourceCandidateFailure.LanguageChanged -> CandidateMessage.LanguageChanged
            SourceCandidateFailure.NoCards -> CandidateMessage.NoCards
            SourceCandidateFailure.DamagedUpdate -> CandidateMessage.Damaged
            SourceCandidateFailure.Cancelled -> CandidateMessage.Cancelled
            SourceCandidateFailure.InvalidContent -> CandidateMessage.Rejected
        }

    fun ownsCandidateCompletion(runToken: Long, candidateToken: String): Boolean =
        candidateRuns.finish(runToken) &&
            app.settings.pendingSourceCandidate.value?.token.let { it == null || it == candidateToken }

    fun validateCandidate(candidate: io.github.stopcran.kanji.data.PendingSourceCandidate) {
        candidateJob?.cancel()
        val runToken = candidateRuns.start()
        candidateRunning = true
        sourceError = null
        status = SyncStatus.candidate(CandidateMessage.Checking)
        statusSource = "${candidate.source.id}@${candidate.source.branch}"
        candidateStatusActivityRevision = -1L
        candidateStatusActivityEpoch = sync.activityEpoch
        candidateJob = scope.launch {
            try {
                when (val validation = app.contentSync.validateSourceCandidate(candidate.source, candidate.token)) {
                    is SourceCandidateResult.Rejected -> {
                        if (ownsCandidateCompletion(runToken, candidate.token) &&
                            app.settings.isCurrentSourceCandidate(candidate.token)
                        ) {
                            app.contentSync.cancelSourceCandidate(app.settings, candidate.token)
                            if (ownsCandidateCompletion(runToken, candidate.token)) {
                                val selectedSource = app.settings.source.value
                                status = SyncStatus.candidate(candidateFailure(validation.reason))
                                statusSource = "${selectedSource.id}@${selectedSource.branch}"
                                candidateStatusActivityRevision = sync.activityRevisions.value[selectedSource] ?: 0L
                            }
                        }
                    }
                    is SourceCandidateResult.Validated -> {
                        val result = app.contentSync.acceptSourceCandidate(
                            validation.candidate,
                            { app.settings.isCurrentSourceCandidate(candidate.token) },
                            { accepted -> check(app.settings.acceptSourceCandidate(accepted)) },
                        )
                        val candidateStillCurrent = app.settings.isCurrentSourceCandidate(candidate.token)
                        if (result is io.github.stopcran.kanji.data.SyncResult.Failed &&
                            candidateStillCurrent && ownsCandidateCompletion(runToken, candidate.token)
                        ) {
                            app.contentSync.cancelSourceCandidate(app.settings, candidate.token)
                        }
                        val terminalStatus = candidateAcceptanceStatus(
                            result,
                            candidateStillCurrent,
                            app.settings.source.value == candidate.source,
                            ownsCandidateCompletion(runToken, candidate.token),
                        )
                        if (terminalStatus != null) {
                            val selectedSource = app.settings.source.value
                            status = terminalStatus
                            statusSource = "${selectedSource.id}@${selectedSource.branch}"
                            candidateStatusActivityRevision = sync.activityRevisions.value[selectedSource] ?: 0L
                            if (terminalStatus.candidateMessage == CandidateMessage.Accepted) {
                                AppLocale.apply(context, AppLanguage.forBaseLanguage(validation.candidate.baseLanguage))
                            }
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("SourceCandidate", "Candidate validation failed", error)
                if (ownsCandidateCompletion(runToken, candidate.token) &&
                    app.settings.isCurrentSourceCandidate(candidate.token)
                ) {
                    app.contentSync.cancelSourceCandidate(app.settings, candidate.token)
                    if (ownsCandidateCompletion(runToken, candidate.token)) {
                        val selectedSource = app.settings.source.value
                        status = SyncStatus.candidate(CandidateMessage.Rejected)
                        statusSource = "${selectedSource.id}@${selectedSource.branch}"
                        candidateStatusActivityRevision = sync.activityRevisions.value[selectedSource] ?: 0L
                    }
                }
            } finally {
                if (candidateRuns.finish(runToken)) {
                    candidateJob = null
                    candidateRunning = false
                }
            }
        }
    }

    fun requestLanguage(language: AppLanguage) {
        val targetLanguage = AppLocale.resolvedLanguage(language)
        scope.launch {
            cancelCandidate()
            val selectedSource = app.settings.source.value
            val acceptedLanguage = app.db.content().meta(selectedSource.id)?.baseLanguage
                ?: app.settings.sync.baseLanguage(selectedSource.id)
            languageConfirmation = if (!LanguageSources.isDefault(selectedSource) &&
                acceptedLanguage != null && acceptedLanguage != targetLanguage
            ) language.storedValue else null
            if (LanguageSources.isDefault(selectedSource)) {
                val target = LanguageSources.defaultForBase(targetLanguage)
                if (target != selectedSource) {
                    url = "https://github.com/${target.owner}/${target.repo}"
                    branch = target.branch
                }
            }
            AppLocale.apply(context, language)
        }
    }

    LaunchedEffect(pendingCandidate?.token) {
        status = status.withCurrentLocaleCandidateMessage(context)
        val candidate = pendingCandidate
        if (candidate == null) {
            status = status.withoutOrphanedCandidateCheck()
            return@LaunchedEffect
        }
        url = candidate.url
        branch = candidate.branch
        validateCandidate(candidate)
    }

    DisposableEffect(Unit) {
        onDispose { candidateJob?.cancel() }
    }

    LaunchedEffect(Unit) {
        if (activity == null) status = status.afterRecreation(context)
    }
    LaunchedEffect(source, activity, sourceActivityRevision) {
        val newerActivityStatus = SyncStatus.fromActivity(activity, context)
        if (newerActivityStatus != null &&
            currentTerminalCandidateStatus(
                status,
                SettingsStatusProvenance(
                    statusSource,
                    sourceKey,
                    candidateStatusActivityRevision,
                    sourceActivityRevision,
                    candidateStatusActivityEpoch == sync.activityEpoch,
                ),
            ) == null
        ) {
            status = newerActivityStatus
            statusSource = sourceKey
            candidateStatusActivityRevision = -1L
        }
    }

    fun saveAndSync() {
        sourceError = null
        status = SyncStatus()
        scope.launch {
            cancelCandidate()
            if (app.contentSync.beginSourceCandidate(app.settings, url, branch) == null) {
                sourceError = context.getString(R.string.invalid_repository_url)
            }
        }
    }

    Page(stringResource(R.string.settings_title), onBack, ime = true) {
        Text(stringResource(R.string.app_language), style = MaterialTheme.typography.titleMedium)
        listOf(AppLanguage.System, AppLanguage.English, AppLanguage.Russian).forEach { language ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(
                    selected = appLanguage == language,
                    onClick = { if (appLanguage != language) requestLanguage(language) },
                )
                Text(
                    stringResource(
                        when (language) {
                            AppLanguage.System -> R.string.language_system
                            AppLanguage.English -> R.string.language_english
                            AppLanguage.Russian -> R.string.language_russian
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.studying), style = MaterialTheme.typography.titleMedium)
        val committed = Commitment.of(dailyNew.toIntOrNull() ?: -1)
        CommitmentChoices(committed) { c ->
            dailyNew = c.perDay.toString()
            app.settings.setDailyNewCards(c.perDay)
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
        val dayNames = listOf(
            R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed, R.string.weekday_thu,
            R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun,
        )
        Text(stringResource(R.string.weekly_rhythm_tap_a_day), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            week.forEachIndexed { i, level ->
                OutlinedButton(
                    onClick = { app.settings.setWeekPlan(DayLevel.encode(week.toMutableList().also { it[i] = level.next() })) },
                    modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(dayNames[i]), style = MaterialTheme.typography.labelSmall)
                        Text(
                            stringResource(
                                when (level) {
                                    DayLevel.Full -> R.string.day_full
                                    DayLevel.Light -> R.string.day_light
                                    DayLevel.Off -> R.string.day_rest
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        Text(
            stringResource(R.string.daily_counts_settings),
            style = MaterialTheme.typography.bodySmall,
        )
        DailyCountsHelp()
        val vary by app.settings.varyFonts.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.vary_kanji_fonts_as_cards), modifier = Modifier.weight(1f))
            Switch(vary, { app.settings.setVaryFonts(it) })
        }
        HorizontalDivider()
        val rule by app.settings.unlockRule.collectAsState()
        Text(stringResource(R.string.unlock_draw_readings), style = MaterialTheme.typography.titleSmall)
        io.github.stopcran.kanji.core.unlock.UnlockRule.entries.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(rule == r, { app.settings.setUnlockRule(r) })
                Column {
                    Text(stringResource(unlockRuleLabel(r)))
                    Text(stringResource(unlockRuleHintResource(r)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val focusOn by app.settings.focusEnabled.collectAsState()
        val focusSize by app.settings.focusSize.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.focus_limit_title), modifier = Modifier.weight(1f))
            Switch(focusOn, { app.settings.setFocusEnabled(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.focus_size, focusSize), modifier = Modifier.weight(1f))
            OutlinedButton({ app.settings.setFocusSize(focusSize - 1) }) { Text("−") }
            OutlinedButton({ app.settings.setFocusSize(focusSize + 1) }) { Text("+") }
        }
        Text(
            stringResource(R.string.focus_limit_description),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        val n4 by app.settings.n4Unlocked.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offer_n4_and_all_word_decks), modifier = Modifier.weight(1f))
            Switch(n4, { app.settings.setN4Unlocked(it) })
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
            if (granted) enableReminder() else status = SyncStatus(context.getString(R.string.notifications_are_blocked_for_this))
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
            stringResource(R.string.reminder_schedule_description),
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        Text(stringResource(R.string.drawing), style = MaterialTheme.typography.titleMedium)
        val brush by app.settings.brush.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrushStyle.entries.forEach { b ->
                androidx.compose.material3.FilterChip(
                    brush == b,
                    { app.settings.setBrush(b) },
                    label = { Text(stringResource(brushLabel(b)), maxLines = 2) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(stringResource(brushHint(brush)), style = MaterialTheme.typography.bodySmall)
        BrushPreview(brush)
        HorizontalDivider()
        Text(stringResource(R.string.advanced), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.content_repository), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.repository_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (customLanguageMismatch) {
            val sourceName = context.getString(if (acceptedLanguage == "ru") R.string.language_russian_name else R.string.language_english_name)
            val interfaceName = context.getString(if (interfaceLanguage == "ru") R.string.language_russian_name else R.string.language_english_name)
            Text(
                stringResource(R.string.custom_source_language_mismatch, sourceName, interfaceName),
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = { requestLanguage(appLanguage) }) {
                Text(stringResource(R.string.switch_to_matching_default))
            }
        }
        OutlinedTextField(
            url,
            { value ->
                if (value != url) {
                    scope.launch { cancelCandidate() }
                }
                url = value
            },
            label = { Text(stringResource(R.string.repository_url)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                imeAction = androidx.compose.ui.text.input.ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        OutlinedTextField(
            branch,
            { value ->
                if (value != branch) {
                    scope.launch { cancelCandidate() }
                }
                branch = value
            },
            label = { Text(stringResource(R.string.branch)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        val defaultSource = LanguageSources.default(appLanguage)
        RepositorySourceActions(
            saveEnabled = !syncing && !candidateRunning,
            onSaveAndSync = ::saveAndSync,
            onUseDefault = {
                scope.launch { cancelCandidate() }
                url = "https://github.com/${defaultSource.owner}/${defaultSource.repo}"
                branch = defaultSource.branch
            },
        )

        if (AppLocale.resolvedLanguage(appLanguage) == "ru") {
            OutlinedButton(onClick = {
                scope.launch { cancelCandidate() }
                url = "https://github.com/${LanguageSources.russianPilot.owner}/${LanguageSources.russianPilot.repo}"
                branch = LanguageSources.russianPilot.branch
            }) { Text(stringResource(R.string.language_russian_pilot)) }
        }
        SyncStatusText(displayedStatus)
        HorizontalDivider()
        Text(stringResource(R.string.voice_input), style = MaterialTheme.typography.titleSmall)
        val voiceOn by app.settings.voiceInput.collectAsState()
        val voiceLanguage = meta?.baseLanguage ?: app.settings.sync.baseLanguage(source.id) ?: AppLocale.resolvedLanguage(appLanguage)
        val voiceStatus by rememberVoiceStatus(voiceLanguage)
        when (val v = voiceStatus) {
            VoiceStatus.Checking -> Text(stringResource(R.string.checking_this_device), style = MaterialTheme.typography.bodySmall)
            is VoiceStatus.Unavailable -> Text(stringResource(R.string.not_available_on_this_device, v.reason), style = MaterialTheme.typography.bodySmall)
            VoiceStatus.Available -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (voiceLanguage == "ru") R.string.voice_meaning_ru else R.string.voice_meaning_en),
                    modifier = Modifier.weight(1f),
                )
                Switch(voiceOn, { app.settings.setVoiceInput(it) })
            }
        }

        Text(
            stringResource(R.string.voice_input_description),
            style = MaterialTheme.typography.bodySmall,
        )
        val traceNew by app.settings.traceNew.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trace_new_kanji), modifier = Modifier.weight(1f))
            Switch(traceNew, { app.settings.setTraceNew(it) })
        }
        Text(stringResource(R.string.handwriting_data), style = MaterialTheme.typography.titleSmall)
        val save by app.settings.saveDrawings.collectAsState()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.save_my_drawings_on_this), modifier = Modifier.weight(1f))
            Switch(save, { app.settings.setSaveDrawings(it) })
        }
        Text(
            stringResource(R.string.drawings_private_storage),
            style = MaterialTheme.typography.bodySmall,
        )
        var saved by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { io.github.stopcran.kanji.data.DrawingLog.count(context) } }
        val scope = rememberCoroutineScope()
        var exportStatus by rememberSaveable { mutableStateOf("") }
        val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) {
                scope.launch {
                    exportStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val n = context.contentResolver.openOutputStream(uri, "wt")
                                ?.use { io.github.stopcran.kanji.data.DrawingLog.exportZip(context, it) }
                                ?: error(context.getString(R.string.cannot_open_file))
                            context.getString(R.string.export_drawings_done, n, java.time.LocalTime.now().withNano(0))
                        }.getOrElse { context.getString(R.string.export_failed, it.message.orEmpty()) }
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
            stringResource(R.string.drawings_backup_warning),
            style = MaterialTheme.typography.bodySmall,
        )
        if (exportStatus.isNotEmpty()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
        var progressStatus by rememberSaveable { mutableStateOf("") }
        val progressExporter = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    progressStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            val n = context.contentResolver.openOutputStream(uri, "wt")
                                ?.use { io.github.stopcran.kanji.data.ProgressExport.write(app.db, app.settings, it) }
                                ?: error(context.getString(R.string.cannot_open_file))
                            context.getString(R.string.export_progress_done, n, java.time.LocalTime.now().withNano(0))
                        }.getOrElse { context.getString(R.string.export_failed, it.message.orEmpty()) }
                    }
                }
            }
        }
        OutlinedButton(onClick = {
            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            progressExporter.launch("kanji-progress-$stamp.zip")
        }) { Text(stringResource(R.string.export_progress)) }
        Text(
            stringResource(R.string.progress_export_description),
            style = MaterialTheme.typography.bodySmall,
        )
        if (progressStatus.isNotEmpty()) Text(progressStatus, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onAbout) { Text(stringResource(R.string.about_sources_and_licences)) }
        Text(
            stringResource(R.string.app_version, io.github.stopcran.kanji.BuildConfig.VERSION_NAME, io.github.stopcran.kanji.BuildConfig.GIT_SHA),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            meta?.let { stringResource(R.string.content_version, it.contentVersion) } ?: stringResource(R.string.content_not_synced),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    languageConfirmation?.let { selected ->
        val language = AppLanguage.parse(selected)
        val targetLanguage = AppLocale.resolvedLanguage(language)
        val sourceLanguage = meta?.baseLanguage ?: app.settings.sync.baseLanguage(source.id) ?: "en"
        val sourceName = context.getString(if (sourceLanguage == "ru") R.string.language_russian_name else R.string.language_english_name)
        val targetName = context.getString(if (targetLanguage == "ru") R.string.language_russian_name else R.string.language_english_name)
        AlertDialog(
            onDismissRequest = { languageConfirmation = null },
            title = { Text(stringResource(R.string.language_source_mismatch_title)) },
            text = { Text(stringResource(R.string.language_source_mismatch_body, sourceName, targetName)) },
            confirmButton = {
                TextButton(onClick = {
                    val target = LanguageSources.defaultForBase(targetLanguage)
                    scope.launch {
                        if (app.contentSync.selectDefaultSource(app.settings, target, language)) {
                            url = "https://github.com/${target.owner}/${target.repo}"
                            branch = target.branch
                            sync.request(target, io.github.stopcran.kanji.data.SyncKind.Ordinary)
                        }
                        languageConfirmation = null
                    }
                }) { Text(stringResource(R.string.language_use_default_source)) }
            },
            dismissButton = {
                TextButton(onClick = { languageConfirmation = null }) { Text(stringResource(R.string.language_keep_source)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommitmentChoices(selected: Commitment?, onSelected: (Commitment) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Commitment.entries.forEach { commitment ->
            androidx.compose.material3.FilterChip(
                selected = selected == commitment,
                onClick = { onSelected(commitment) },
                label = {
                    Text(
                        stringResource(
                            R.string.label_with_count,
                            stringResource(commitmentLabel(commitment)),
                            commitment.perDay,
                        ),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RepositorySourceActions(
    saveEnabled: Boolean,
    onSaveAndSync: () -> Unit,
    onUseDefault: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onSaveAndSync, enabled = saveEnabled) { Text(stringResource(R.string.save_sync)) }
        OutlinedButton(onClick = onUseDefault) { Text(stringResource(R.string.use_default_repo)) }
    }
}

private fun commitmentLabel(value: Commitment): Int = when (value) {
    Commitment.Light -> R.string.commitment_light
    Commitment.Steady -> R.string.commitment_steady
    Commitment.Intensive -> R.string.commitment_intensive
}

private fun unlockRuleLabel(value: io.github.stopcran.kanji.core.unlock.UnlockRule): Int = when (value) {
    io.github.stopcran.kanji.core.unlock.UnlockRule.NextDay -> R.string.unlock_next_day
    io.github.stopcran.kanji.core.unlock.UnlockRule.SpacedSameDay -> R.string.unlock_spaced_same_day
}

internal fun unlockRuleHintResource(value: io.github.stopcran.kanji.core.unlock.UnlockRule): Int = when (value) {
    io.github.stopcran.kanji.core.unlock.UnlockRule.NextDay -> R.string.unlock_next_day_hint
    io.github.stopcran.kanji.core.unlock.UnlockRule.SpacedSameDay -> R.string.unlock_spaced_same_day_hint
}

private fun brushLabel(value: BrushStyle): Int = when (value) {
    BrushStyle.Dot -> R.string.brush_dot
    BrushStyle.Chisel -> R.string.brush_chisel
    BrushStyle.Soft -> R.string.brush_soft
    BrushStyle.Ink -> R.string.brush_ink
}

private fun brushHint(value: BrushStyle): Int = when (value) {
    BrushStyle.Dot -> R.string.brush_dot_hint
    BrushStyle.Chisel -> R.string.brush_chisel_hint
    BrushStyle.Soft -> R.string.brush_soft_hint
    BrushStyle.Ink -> R.string.brush_ink_hint
}
