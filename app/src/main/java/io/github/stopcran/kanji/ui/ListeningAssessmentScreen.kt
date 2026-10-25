package io.github.stopcran.kanji.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.stopcran.kanji.core.listening.*

@Composable
fun ListeningAssessmentScreen(id: String, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    val vm = serviceViewModel(id) { ListeningAssessmentViewModel(it, id) }
    ListeningAssessmentView(vm, onBack, onOpenDoc)
}

@Composable
internal fun ListeningAssessmentView(vm: ListeningAssessmentViewModel, onBack: () -> Unit, onOpenDoc: (String, String) -> Unit) {
    AssessmentLifecycle(vm)
    var ending by remember { mutableStateOf(false) }
    var article by rememberSaveable { mutableStateOf<String?>(null) }
    val current = vm.snapshot
    val state = current?.state
    val task = vm.displayTask
    val scroll = key(current?.row?.id, state?.index, state?.phase, vm.detail?.id) { rememberScrollState() }
    fun leave() {
        when {
            article != null -> article = null
            vm.detail != null -> vm.hideDetail()
            current?.result != null -> vm.close(onBack)
            else -> { vm.pause("Left assessment", leave = true); onBack() }
        }
    }
    BackHandler(onBack = ::leave)
    val body = vm.words[article]
    if (body != null && current != null) {
        Page(requireNotNull(article), { article = null }, scrollable = false) {
            LazyMarkdownView(body, "words/$article.md", { onOpenDoc(current.row.sourceId, it) }, Modifier.weight(1f))
        }
        return
    }
    Page("Japanese diagnostic section", ::leave, scrollable = false, maxWidth = 1100.dp) {
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val unavailable = vm.error != null || !vm.ready
        if (current?.row?.closedAtMs != null) {
            Text("This assessment is closed.")
            Button(onClick = onBack) { Text("Back to Listening") }
        } else if (current?.result != null && vm.detail == null) {
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListeningAssessmentResults(requireNotNull(current.result), vm::showDetail)
                Button(onClick = { vm.close(onBack) }, enabled = !vm.busy) { Text("Done") }
            }
        } else if (unavailable || current == null || task == null) {
            Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.error == null) Text("Loading the captured section...")
                else {
                    Text(requireNotNull(vm.error), color = MaterialTheme.colorScheme.error)
                    Text("Technical failure is not a wrong answer. Recovery cannot restore the uninterrupted label.")
                    OutlinedButton(onClick = vm::retry, enabled = !vm.busy) { Text("Retry captured evidence") }
                    if (current != null) TextButton(onClick = vm::end, enabled = !vm.busy) { Text("End with partial results") }
                    TextButton(onClick = { vm.close(onBack, discardUnreadable = true) }, enabled = !vm.busy) {
                        Text("Discard unreadable capture; keep finalized data")
                    }
                }
            }
        } else {
            val hideEvidence = state?.phase in setOf(AssessmentPhase.Ready, AssessmentPhase.Instruction) && !vm.help
            val picture = vm.picture.takeUnless { hideEvidence }
            val evidenceId = requireNotNull(vm.displayRow).id
            val evidence = Modifier.onGloballyPositioned {
                if (!it.boundsInWindow().isEmpty) {
                    vm.evidenceVisible(evidenceId, current.state.generation, current.state.phase, vm.help || vm.detail != null)
                }
            }
            key(evidenceId, current.state.phase) {
                ListeningTaskLayout(picture, scroll, pictureView = { modifier ->
                    picture?.let {
                        ListeningTaskPicture(it, task.visual?.role == "required", task.choiceMode, modifier, evidence,
                            onEnlarge = { vm.pause("Picture enlargement") },
                            enlargementLabel = if (current.result == null) "Enlarge picture (interrupts section)" else "Enlarge picture")
                    }
                }) {
                    if (vm.detail != null) {
                        Text(task.title, style = MaterialTheme.typography.titleLarge)
                        ListeningTaskFeedbackBody(task, current.result?.responses?.find { it.encounterId == vm.detail?.id }?.answer,
                            evidence, vm.words, { vm.pause("Reading captured word"); article = it }, { vm.replay(it) }, !vm.busy && !vm.playing)
                        TextButton(onClick = vm::hideDetail) { Text("Back to results") }
                    } else {
                        ListeningAssessmentItem(vm, evidence)
                        TextButton(onClick = { vm.pause("End confirmation"); ending = true }, enabled = !vm.busy) {
                            Text("End with partial results")
                        }
                    }
                }
            }
        }
    }
    if (ending) AlertDialog(onDismissRequest = { ending = false }, title = { Text("End this section?") },
        text = { Text("Finalized responses remain. Unfinished and unvisited items are not assessed; " +
            "no exposure is invented. Scored feedback will be released.") },
        confirmButton = { TextButton(onClick = { ending = false; vm.end() }) { Text("End section") } },
        dismissButton = { TextButton(onClick = { ending = false }) { Text("Keep interrupted session") } })
}

@Composable
private fun ListeningAssessmentItem(vm: ListeningAssessmentViewModel, evidence: Modifier) {
    val current = requireNotNull(vm.snapshot)
    val state = current.state
    val task = current.record.task
    val example = task.usage == TaskUsage.Example
    Text(task.taskType.label, style = MaterialTheme.typography.titleLarge)
    Text(if (example) "Worked example - not in the score" else
        "Scored item ${current.form.items.take(state.index).count { !it.example } + 1}/${current.form.items.count { !it.example }}")
    if (state.paused) {
        Text("Paused. ${state.interruptions.joinToString()}")
        Button(onClick = vm::continuePractice, enabled = !vm.busy && !vm.playing) { Text("Continue as interrupted practice") }
        OutlinedButton(onClick = vm::showHelp, enabled = !vm.busy && !vm.playing) { Text("Show transcript and translation (help)") }
        OutlinedButton(onClick = { vm.replay(state.stageIndex, slower = true) }, enabled = !vm.busy && !vm.playing) {
            Text("Replay current stage more slowly (help)")
        }
    } else if (state.phase == AssessmentPhase.Ready) {
        Text("The captured form is fixed. Begin starts its instructions, playback and timed windows.")
        Button(onClick = vm::begin, enabled = !vm.busy) { Text("Begin section") }
    }
    when (state.phase) {
        AssessmentPhase.Instruction -> task.stages.first().turns.forEach { id -> Text(task.turns.single { it.id == id }.text) }
        AssessmentPhase.Preview -> Text("Read / look: ${((state.remainingMs ?: 0) + 999) / 1_000}s")
        AssessmentPhase.Audio -> Text("Listening: ${task.stages[state.stageIndex].label(task)}")
        AssessmentPhase.Answer -> Text("Answer window: ${((state.remainingMs ?: 0) + 999) / 1_000}s")
        AssessmentPhase.ExampleFeedback -> {
            Text("Worked example answer / explanation: ${((state.remainingMs ?: 0) + 999) / 1_000}s")
            val correct = task.options.single { it.id == task.answer }
            Text("Correct: ${correct.number}. ${correct.label}")
            Text(task.explanation, Modifier.onGloballyPositioned {
                if (!it.boundsInWindow().isEmpty) {
                    vm.evidenceVisible(current.encounter.id, state.generation, state.phase, textHelp = true, explanation = true)
                }
            })
        }
        else -> Unit
    }
    if (state.phase !in setOf(AssessmentPhase.Ready, AssessmentPhase.Instruction, AssessmentPhase.ExampleFeedback)) {
        ListeningTaskChoices(task, state.answer, state.phase == AssessmentPhase.Answer && !state.paused && !state.skipped && !vm.busy,
            vm.help, { vm.answer(it) }, evidence)
        if (state.phase == AssessmentPhase.Answer) {
            if (state.skipped) Text("Skipped - unanswered is locked. The clock does not advance early.")
            else OutlinedButton(onClick = { vm.answer(null) }, enabled = !state.paused && !vm.busy) { Text("Skip (lock unanswered)") }
        }
    }
    if (!state.paused && state.phase != AssessmentPhase.Ready) {
        Button(onClick = { vm.pause() }, enabled = !vm.busy) { Text("Pause (interrupts section)") }
    }
    Text(if (state.interrupted) "Interrupted practice - never an uninterrupted full-form attempt" else "Single-play diagnostic")
    Text("N${current.form.jlpt} - ${current.row.sourceId} (${current.row.branch})", style = MaterialTheme.typography.bodySmall)
    if (vm.help) {
        task.visual?.let { Text(it.description, evidence) }
        ListeningTaskTranscript(task, evidence, { vm.replay(it) }, !vm.busy && !vm.playing)
    }
}

@Composable
private fun ListeningAssessmentResults(result: AssessmentResult, detail: (Int) -> Unit) {
    Text(if (result.completedFullForm) "Completed full-form diagnostic" else "Partial / interrupted diagnostic", style = MaterialTheme.typography.headlineSmall)
    Text("N${result.jlpt} - ${result.blueprint}", style = MaterialTheme.typography.bodySmall)
    Text("${result.correct} / ${result.assessed} correct", style = MaterialTheme.typography.titleLarge)
    Text("Unanswered: ${result.unanswered}; not assessed: ${result.notAssessed}; planned scored items: ${result.planned}; examples: ${result.examples}.")
    Text("No SRS or daily-allowance changes. Familiar or helped performance does not prove unseen-listening proficiency.")
    Text("No official JLPT scaling, pass prediction or certification.")
    if (result.interrupted) Text("Interruptions: ${result.interruptions.joinToString()}")
    ListeningTaskType.entries.forEach { type ->
        val responses = result.scored.filter { it.type == type }
        Text("${type.label}: ${responses.count { it.outcome == AssessmentOutcome.Correct }} / " +
            "${responses.count { it.outcome != AssessmentOutcome.NotAssessed }} assessed")
    }
    result.responses.forEachIndexed { index, response ->
        HorizontalDivider()
        val outcome = if (response.outcome == AssessmentOutcome.NotAssessed) "Not assessed" else response.outcome.name
        Text("${index + 1}. ${response.type.label}${if (response.example) " - worked example" else ""}: $outcome")
        Text("${if (response.familiar) "Familiar at capture" else "Unseen at capture"}; " +
            "${if (response.exposed) "exposed during section" else "not exposed during section"}; picture: ${response.visual}.")
        if (response.assistance.isNotEmpty()) Text("Help: ${response.assistance.joinToString()}")
        if (response.interrupted) Text("Finalized during interrupted practice.")
        TextButton(onClick = { detail(index) }) { Text("Feedback and captured transcript") }
    }
}

@Composable
private fun AssessmentLifecycle(vm: ListeningAssessmentViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(vm) { vm.load() }
    DisposableEffect(vm, lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.pause("App paused or backgrounded", leave = true)
            if (event == Lifecycle.Event.ON_RESUME) vm.load()
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) vm.pause("Window focus lost") }
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { vm.pause("Headphones disconnected") }
        }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(noisy, filter)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
            context.unregisterReceiver(noisy)
            vm.pause("Left assessment screen", leave = true)
        }
    }
}
