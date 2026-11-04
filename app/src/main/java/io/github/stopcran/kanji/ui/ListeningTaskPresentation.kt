package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.listening.ListeningTaskType
import io.github.stopcran.kanji.core.listening.ListeningTaskPackage
import io.github.stopcran.kanji.core.listening.TaskStage
import io.github.stopcran.kanji.core.listening.TaskStageRole
import io.github.stopcran.kanji.core.srs.Grade

@Composable
internal fun ListeningTaskType.label(): String = stringResource(labelResource())

internal fun ListeningTaskType.labelResource(): Int = when (this) {
    ListeningTaskType.TaskBased -> R.string.listening_type_task_based
    ListeningTaskType.KeyPoint -> R.string.listening_type_key_point
    ListeningTaskType.VerbalExpression -> R.string.listening_type_verbal_expression
    ListeningTaskType.QuickResponse -> R.string.listening_type_quick_response
}

@Composable
internal fun ListeningTaskType.objective(): String = stringResource(when (this) {
    ListeningTaskType.TaskBased -> R.string.listening_objective_task_based
    ListeningTaskType.KeyPoint -> R.string.listening_objective_key_point
    ListeningTaskType.VerbalExpression -> R.string.listening_objective_verbal_expression
    ListeningTaskType.QuickResponse -> R.string.listening_objective_quick_response
})

@Composable
internal fun TaskStageRole.label(): String = stringResource(when (this) {
    TaskStageRole.Instruction -> R.string.listening_stage_instructions
    TaskStageRole.Focus -> R.string.listening_stage_focus_question
    TaskStageRole.Passage -> R.string.listening_stage_passage
    TaskStageRole.FocusRepeat -> R.string.listening_stage_repeated_focus
    TaskStageRole.Situation -> R.string.listening_stage_situation
    TaskStageRole.Utterance -> R.string.listening_stage_utterance
    TaskStageRole.Choice -> R.string.listening_stage_spoken_choice
})

@Composable
internal fun TaskStage.label(task: ListeningTaskPackage): String =
    optionId?.let { id -> stringResource(R.string.listening_stage_spoken_choice_number, task.options.single { it.id == id }.number) } ?: role.label()

@Composable
internal fun Grade.localizedLabel(): String = stringResource(when (this) {
    Grade.Again -> R.string.grade_again
    Grade.Hard -> R.string.grade_hard
    Grade.Good -> R.string.grade_good
    Grade.Easy -> R.string.grade_easy
})

@Composable
internal fun listeningAssistanceLabel(value: String): String = stringResource(when (value) {
    "guessed" -> R.string.listening_help_guess
    "extra-replay" -> R.string.listening_help_extra_replay
    "partial-replay" -> R.string.listening_help_partial_replay
    "targeted-replay" -> R.string.listening_help_targeted_replay
    "slower-playback" -> R.string.listening_help_slower_playback
    "recovery-replay" -> R.string.listening_help_recovery_replay
    "text-help" -> R.string.listening_help_transcript
    else -> R.string.listening_help_other
})

@Composable
internal fun listeningAssistanceList(values: Iterable<String>): String {
    val labels = mutableListOf<String>()
    for (value in values) labels += listeningAssistanceLabel(value)
    return labels.joinToString()
}
