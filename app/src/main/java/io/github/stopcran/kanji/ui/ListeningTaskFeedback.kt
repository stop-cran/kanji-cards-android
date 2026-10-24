package io.github.stopcran.kanji.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.stopcran.kanji.core.srs.Grade

@Composable
internal fun ListeningTaskFeedback(vm: ListeningTaskViewModel, onWord: (String) -> Unit) {
    val task = requireNotNull(vm.task)
    vm.response?.let { result ->
        val outcome = if (result.giveUp) "Given up" else if (result.correct == true) "Correct" else "Incorrect"
        Text("$outcome - ${Grade.entries.single { it.value == result.grade }.name}",
            style = MaterialTheme.typography.headlineSmall)
        Text(if (vm.encounter?.firstEncounter == true) "First encounter in this source" else "Previously encountered story")
        Text("This result measures this task, not unseen-listening proficiency.", style = MaterialTheme.typography.bodySmall)
        if (result.assistance.isNotEmpty()) Text("Help used: ${result.assistance.joinToString()}")
    }
    task.options.forEach { option ->
        HorizontalDivider()
        val prefix = if (option.id == task.answer) "Correct: " else if (option.id == vm.response?.answer) "Your answer: " else ""
        Text("$prefix${option.number}. ${option.label}", Modifier.listeningEvidence(vm))
        Text(option.translation)
        Text(option.rationale, style = MaterialTheme.typography.bodySmall)
    }
    Text(task.explanation, Modifier.listeningEvidence(vm))
    task.visual?.let { Text(it.description) }
    Text("Supporting evidence", style = MaterialTheme.typography.titleMedium)
    task.evidence.forEach { id ->
        val turn = task.turns.single { it.id == id }
        Text(turn.text, Modifier.listeningEvidence(vm))
        Text(turn.translation)
    }
    ListeningTaskTranscript(vm)
    if (task.vocabulary.isNotEmpty()) Text("Vocabulary", style = MaterialTheme.typography.titleLarge)
    val words = vm.vocabularyBodies
    task.vocabulary.forEach { word ->
        Text("${word.surface} - ${word.reading}: ${word.gloss}", Modifier.listeningEvidence(vm))
        word.word?.takeIf { it in words }?.let { target ->
            TextButton(onClick = { onWord(target) }) { Text("Read captured word article: ${word.surface}") }
        }
    }
}

@Composable
internal fun ListeningTaskTranscript(vm: ListeningTaskViewModel) {
    val task = requireNotNull(vm.task)
    Text("Transcript and translation", style = MaterialTheme.typography.titleLarge)
    task.stages.forEachIndexed { index, stage ->
        Text(stage.label(task), style = MaterialTheme.typography.titleMedium)
        stage.turns.forEach { id ->
            val turn = task.turns.single { it.id == id }
            Text("${turn.speaker}: ${turn.text}", Modifier.listeningEvidence(vm))
            Text(turn.translation)
        }
        TextButton(onClick = { vm.play(stage = index) }, enabled = !vm.busy && vm.error == null) { Text("Replay stage ${index + 1}") }
    }
}
