package io.github.stopcran.kanji.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.listening.ListeningTaskPackage
import io.github.stopcran.kanji.core.srs.Grade

@Composable
internal fun ListeningTaskFeedback(vm: ListeningTaskViewModel, onWord: (String) -> Unit) {
    val task = requireNotNull(vm.task)
    vm.response?.let { result ->
        val outcome = when {
            result.giveUp -> stringResource(R.string.listening_given_up)
            result.correct == true -> stringResource(R.string.listening_result_correct)
            else -> stringResource(R.string.listening_result_incorrect)
        }
        val grade = Grade.entries.single { it.value == result.grade }.localizedLabel()
        Text(stringResource(R.string.listening_task_outcome_grade, outcome, grade), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(if (vm.encounter?.firstEncounter == true) R.string.listening_first_encounter_source
            else R.string.listening_previously_encountered_story))
        Text(stringResource(R.string.listening_task_result_scope), style = MaterialTheme.typography.bodySmall)
        if (result.assistance.isNotEmpty()) {
            Text(stringResource(R.string.listening_help_used, listeningAssistanceList(result.assistance)))
        }
    }
    ListeningTaskFeedbackBody(task, vm.response?.answer, Modifier.listeningEvidence(vm), vm.vocabularyBodies,
        onWord, { vm.play(stage = it) }, !vm.busy && vm.error == null)
}

@Composable
internal fun ListeningTaskFeedbackBody(
    task: ListeningTaskPackage, answer: String?, evidence: Modifier, words: Map<String, String>,
    onWord: (String) -> Unit, replay: (Int) -> Unit, replayEnabled: Boolean,
) {
    task.options.forEach { option ->
        HorizontalDivider()
        val prefix = when (option.id) {
            task.answer -> stringResource(R.string.listening_correct_prefix, "")
            answer -> stringResource(R.string.listening_your_answer_prefix, "")
            else -> ""
        }
        Text(stringResource(R.string.listening_numbered_option, prefix, option.number, option.label), evidence)
        Text(option.translation)
        Text(option.rationale, style = MaterialTheme.typography.bodySmall)
    }
    Text(task.explanation, evidence)
    task.visual?.let { Text(it.description) }
    Text(stringResource(R.string.listening_supporting_evidence), style = MaterialTheme.typography.titleMedium)
    task.evidence.forEach { id ->
        val turn = task.turns.single { it.id == id }
        Text(turn.text, evidence)
        Text(turn.translation)
    }
    ListeningTaskTranscript(task, evidence, replay, replayEnabled)
    if (task.vocabulary.isNotEmpty()) Text(stringResource(R.string.listening_vocabulary), style = MaterialTheme.typography.titleLarge)
    task.vocabulary.forEach { word ->
        Text("${word.surface} - ${word.reading}: ${word.gloss}", evidence)
        word.word?.takeIf { it in words }?.let { target ->
            TextButton(onClick = { onWord(target) }) { Text(stringResource(R.string.listening_read_captured_word_article, word.surface)) }
        }
    }
}

@Composable
internal fun ListeningTaskTranscript(vm: ListeningTaskViewModel) {
    ListeningTaskTranscript(requireNotNull(vm.task), Modifier.listeningEvidence(vm), { vm.play(stage = it) }, !vm.busy && vm.error == null)
}

@Composable
internal fun ListeningTaskTranscript(task: ListeningTaskPackage, evidence: Modifier, replay: (Int) -> Unit, replayEnabled: Boolean) {
    Text(stringResource(R.string.listening_transcript_translation), style = MaterialTheme.typography.titleLarge)
    task.stages.forEachIndexed { index, stage ->
        Text(stage.label(task), style = MaterialTheme.typography.titleMedium)
        stage.turns.forEach { id ->
            val turn = task.turns.single { it.id == id }
            Text("${turn.speaker}: ${turn.text}", evidence)
            Text(turn.translation)
        }
        TextButton(onClick = { replay(index) }, enabled = replayEnabled) {
            Text(stringResource(R.string.listening_replay_stage, index + 1))
        }
    }
}
