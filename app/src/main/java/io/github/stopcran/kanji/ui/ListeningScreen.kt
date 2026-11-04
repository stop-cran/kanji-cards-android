package io.github.stopcran.kanji.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.withTransaction
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.data.ListeningOverview
import io.github.stopcran.kanji.core.listening.*
import io.github.stopcran.kanji.core.srs.Grade
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

@Composable
internal fun listeningCooldownLocalizedText(until: java.time.Instant): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val formatted = listeningCooldownText(until, locale = locale).removePrefix("Practice only until ")
    return stringResource(R.string.listening_cooldown_until, formatted)
}

@Composable
internal fun CapturedListeningLanguageNotice(language: String?) {
    val captured = language ?: return
    val current = if (LocalConfiguration.current.locales[0]?.language == "ru") "ru" else "en"
    if (captured == current) return
    val context = LocalContext.current
    val capturedName = context.getString(if (captured == "ru") R.string.language_russian_name else R.string.language_english_name)
    val currentName = context.getString(if (current == "ru") R.string.language_russian_name else R.string.language_english_name)
    Text(
        stringResource(R.string.listening_capture_language_mismatch, capturedName, currentName),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun ListeningPassageEvidence(turns: List<ListeningTurn>, references: List<String>) {
    references.distinct().forEach { id ->
        val turn = turns.find { it.id == id }
        Text(stringResource(R.string.listening_evidence_reference, id), style = MaterialTheme.typography.bodySmall)
        if (turn != null) {
            Text(turn.text)
            Text(turn.translation, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.listening_evidence_unavailable_reference), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun rememberListeningHome(app: KanjiApp): State<ListeningHomeState> {
    return rememberListeningHome(app.services)
}

@Composable
internal fun rememberListeningHome(services: Services, format: String = "all"): State<ListeningHomeState> {
    val source by services.settings.source.collectAsState()
    val budget by services.settings.dailyNewCards.collectAsState()
    val week by services.settings.weekPlan.collectAsState()
    val scopes by services.settings.listeningScopes.collectAsState()
    return key(source.id, budget, week, scopes[source.id], format) {
        produceState(ListeningHomeState()) {
            listeningAction({ value = ListeningHomeState(error = it) }) {
                combine(listOf(
                    services.db.listening().observePassages(source.id).map { Unit }, services.db.listening().observeOpen().map { Unit },
                    services.db.listeningTasks().observeTasks(source.id).map { Unit }, services.db.listeningTasks().observeOpen().map { Unit },
                    services.db.listeningTasks().observeSync(source.id).map { Unit },
                    services.db.listeningTasks().observeOpenAssessments().map { Unit },
                    services.db.reviews().observeStates(source.id, LISTENING_STACK, LISTENING_MODE).map { Unit },
                    flow { while (true) { emit(Unit); delay(30_000) } },
                )) { Unit }.collectLatest {
                    value = services.db.withTransaction {
                        ListeningHomeState(services.listening.overview(source.id, format),
                            queue = services.listeningTasks.overview(source.id, format),
                            resumes = services.db.listening().open() + services.db.listeningTasks().open() +
                                services.db.listeningTasks().openAssessments(),
                            assessmentPending = services.db.listeningTasks().activeAssessment(source.id) != null,
                            taskNotice = services.db.listeningTasks().sync(source.id)?.problem)
                    }
                }
            }
        }
    }
}

@Composable
fun ListeningPage(app: KanjiApp, onBack: () -> Unit, onEncounter: (String) -> Unit) {
    val source by app.settings.source.collectAsState()
    key(source) {
        val content = rememberHomeContent(app.db, app.contentSync, source)
        val scope = rememberCoroutineScope()
        ListeningBrowser(app.services, onBack, onEncounter, content) { scope.launch { app.contentSync.sync(source) } }
    }
}

@Composable
internal fun ListeningStartButton(data: ListeningOverview, busy: Boolean, onStart: (String, Boolean) -> Unit) {
    val scheduled = data.due.firstOrNull() ?: data.fresh.firstOrNull()
    val extra = scheduled == null && data.extra.isNotEmpty()
    val next = scheduled ?: data.extra.firstOrNull()
    val enabled = next != null && data.active == null && !busy
    if (extra) {
        OutlinedButton(onClick = { next?.let { onStart(it, true) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.practice_more))
        }
        Text(stringResource(R.string.listening_extra_practice), style = MaterialTheme.typography.bodySmall)
    } else {
        Button(onClick = { next?.let { onStart(it, false) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.listening_start))
        }
    }
}

@Composable
fun ListeningScreen(encounterId: String, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val vm = serviceViewModel(encounterId) { ListeningViewModel(it, encounterId) }
    LaunchedEffect(encounterId) { vm.load() }
    ListeningEncounterView(vm, onBack, onOpenDoc)
}

@Composable
internal fun ListeningEncounterView(vm: ListeningViewModel, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vm, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.pause(release = true)
            if (event == Lifecycle.Event.ON_START) vm.load()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.pause(release = true) }
    }
    var discard by remember { mutableStateOf(false) }
    var vocabulary by rememberSaveable { mutableStateOf<String?>(null) }
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    val row = vm.encounter
    val passage = vm.passage
    val attempt = vm.attempt
    fun leave() {
        if (row?.kind == "study" || row?.reviewLogId != null) vm.close(false, onBack)
        else { vm.pause(release = true); onBack() }
    }
    BackHandler { if (article != null) article = null else leave() }
    val capturedArticle = article?.let { attempt?.vocabularyBodies?.get(it) }
    if (capturedArticle != null && row != null) {
        Page(requireNotNull(article), { article = null }, scrollable = false) {
            LazyMarkdownView(capturedArticle, "words/$article.md", { onOpenDoc(row.sourceId, it) }, Modifier.weight(1f))
        }
        return
    }
    val finished = row?.kind == "study" || row?.reviewLogId != null
    val canFinish = finished && row?.closedAtMs == null && passage != null && attempt != null
    key(row?.id, attempt?.phase) { Page(passage?.title ?: stringResource(R.string.listening_passage_title), ::leave,
        footer = if (canFinish) { {
            Button(onClick = { vm.close(false, onBack) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.listening_done))
            }
        } } else null,
    ) {
        CapturedListeningLanguageNotice(vm.capturedLanguage)
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.error?.let {
            Text(stringResource(R.string.listening_task_error_generic), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.listening_no_failure_playback_error))
            Button(onClick = vm::retry, enabled = !vm.busy) { Text(stringResource(R.string.listening_retry)) }
        }
        if (row == null || passage == null || attempt == null) {
            if (vm.error == null) Text(stringResource(R.string.listening_loading_captured_recording))
        } else if (row.closedAtMs != null) {
            Text(stringResource(R.string.listening_this_encounter_complete))
            Button(onClick = onBack) { Text(stringResource(R.string.listening_back)) }
        } else {
            val study = row.kind == "study"
            val feedback = study || row.reviewLogId != null
            val ready = !vm.busy && vm.error == null
            Text(stringResource(R.string.listening_legacy_source_detail,
                stringResource(if (study) R.string.listening_unscored_study else R.string.listening_n5_listening),
                row.sourceId), style = MaterialTheme.typography.bodySmall)
            vm.picture?.let { SceneBitmap(it, passage.topic) }
            val audio = requireNotNull(passage.audio)
            Text(stringResource(R.string.listening_seconds_progress, vm.positionMs / 1_000, audio.durationMs / 1_000))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.playing) Button(onClick = { vm.pause() }) { Text(stringResource(R.string.listening_pause)) }
                else Button(onClick = { vm.play(restart = vm.positionMs >= audio.durationMs) }, enabled = ready) {
                    Text(stringResource(if (vm.positionMs >= audio.durationMs) R.string.listening_replay else
                        if (vm.positionMs > 0) R.string.listening_resume_audio else R.string.listening_play))
                }
                OutlinedButton(onClick = { vm.play(restart = true) }, enabled = ready && !vm.playing && attempt.starts > 0) {
                    Text(stringResource(R.string.listening_from_beginning))
                }
            }
            OutlinedButton(onClick = { vm.play(restart = vm.positionMs >= audio.durationMs, slower = true) },
                enabled = ready && !vm.playing) { Text(stringResource(R.string.listening_play_slow_help)) }
            if (!feedback) {
                Text(stringResource(R.string.listening_n5_pace_help))
                if (attempt.assistance.isNotEmpty()) Text(stringResource(R.string.listening_help_used, listeningAssistanceList(attempt.assistance)))
                if (attempt.phase == "questions") {
                    passage.questions.forEach { question ->
                        Text(question.prompt, style = MaterialTheme.typography.titleMedium)
                        attempt.optionOrder.getValue(question.id).forEach { id ->
                            val option = question.options.single { it.id == id }
                            FilterChip(selected = attempt.answers[question.id] == id,
                                onClick = { vm.answer(question.id, id) }, enabled = ready,
                                label = { Text(option.label) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Button(onClick = { vm.submit() }, enabled = ready && attempt.answers.size == 3,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.listening_check_all_answers)) }
                    OutlinedButton(onClick = { vm.submit(guessed = true) }, enabled = ready && attempt.answers.size == 3,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.listening_check_guessed)) }
                } else Text(stringResource(R.string.listening_questions_after_playback))
                TextButton(onClick = { vm.submit(giveUp = true) }, enabled = ready && row.exposedAtMs != null) {
                    Text(stringResource(R.string.listening_cannot_follow))
                }
            } else {
                vm.response?.let { result ->
                    val correct = requireNotNull(result.correct)
                    val percent = (correct * 100.0 / 3).roundToInt()
                    val grade = Grade.entries.single { it.value == result.grade }.localizedLabel()
                    Text(stringResource(R.string.listening_legacy_score, correct, percent, grade),
                        style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(if (row.firstEncounter) R.string.listening_first_encounter_source
                        else R.string.listening_previously_encountered_passage))
                    Text(stringResource(R.string.listening_measures_passage), style = MaterialTheme.typography.bodySmall)
                    if (result.assistance.isNotEmpty()) Text(stringResource(R.string.listening_help_used, listeningAssistanceList(result.assistance)))
                }
                passage.questions.forEach { question ->
                    HorizontalDivider()
                    Text(question.prompt, style = MaterialTheme.typography.titleMedium)
                    (attempt.optionOrder.getValue(question.id)).forEach { id ->
                        val option = question.options.single { it.id == id }
                        val correct = id == question.answer
                        val selected = vm.response?.answers?.get(question.id) == id
                        val label = when {
                            correct -> stringResource(R.string.listening_correct_prefix, option.label)
                            selected -> stringResource(R.string.listening_your_answer_prefix, option.label)
                            else -> option.label
                        }
                        Text(label,
                            color = if (selected && !correct) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Text(option.rationale, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(question.explanation)
                    ListeningPassageEvidence(passage.turns, question.evidence)
                }
                Text(stringResource(R.string.listening_transcript_translation), style = MaterialTheme.typography.titleLarge)
                passage.turns.forEach { turn ->
                    Text("${turn.speaker}: ${turn.text}", style = MaterialTheme.typography.titleMedium)
                    Text(turn.translation)
                    TextButton(onClick = { vm.play(turnId = turn.id) }, enabled = ready && !vm.playing) {
                        Text(stringResource(R.string.listening_replay_turn, turn.id))
                    }
                }
                if (passage.vocabulary.isNotEmpty()) Text(stringResource(R.string.listening_vocabulary), style = MaterialTheme.typography.titleLarge)
                passage.vocabulary.forEach { word ->
                    TextButton(onClick = { vocabulary = word.surface }, modifier = Modifier.fillMaxWidth()) {
                        Text("${word.surface} - ${word.reading}")
                    }
                }
            }
            if (row.reviewLogId == null) TextButton(onClick = { discard = true }, enabled = !vm.busy) {
                Text(stringResource(R.string.listening_discard_encounter))
            }
        }
    } }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.listening_discard_encounter_title)) },
        text = { Text(stringResource(R.string.listening_discard_encounter_message)) },
        confirmButton = { TextButton(onClick = { discard = false; vm.close(true, onBack) }) { Text(stringResource(R.string.listening_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.listening_keep)) } },
    )
    passage?.vocabulary?.find { it.surface == vocabulary }?.let { word ->
        AlertDialog(onDismissRequest = { vocabulary = null }, title = { Text(word.surface) },
            text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                Text(word.reading); Text(word.gloss)
            } },
            confirmButton = { TextButton(onClick = { vocabulary = null }) { Text(stringResource(R.string.listening_close)) } },
            dismissButton = {
                if (word.word in attempt?.vocabularyBodies.orEmpty()) TextButton(onClick = {
                    vm.pause(); article = word.word; vocabulary = null
                }) { Text(stringResource(R.string.listening_read_word_article)) }
            })
    }
}
