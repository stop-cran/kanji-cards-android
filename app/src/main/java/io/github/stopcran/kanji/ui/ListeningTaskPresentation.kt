package io.github.stopcran.kanji.ui

import io.github.stopcran.kanji.core.listening.ListeningTaskType
import io.github.stopcran.kanji.core.listening.TaskStageRole

internal val ListeningTaskType.label: String get() = when (this) {
    ListeningTaskType.TaskBased -> "Task-based comprehension"
    ListeningTaskType.KeyPoint -> "Key-point comprehension"
    ListeningTaskType.VerbalExpression -> "Verbal expression"
    ListeningTaskType.QuickResponse -> "Quick response"
}

internal val ListeningTaskType.objective: String get() = when (this) {
    ListeningTaskType.TaskBased -> "Listen for the information needed to decide what to do."
    ListeningTaskType.KeyPoint -> "Listen for the point specified in the opening question."
    ListeningTaskType.VerbalExpression -> "Choose what the indicated person should say."
    ListeningTaskType.QuickResponse -> "Choose a fitting response to the utterance."
}

internal val TaskStageRole.label: String get() = when (this) {
    TaskStageRole.Instruction -> "Instructions"
    TaskStageRole.Focus -> "Focus question"
    TaskStageRole.Passage -> "Passage"
    TaskStageRole.FocusRepeat -> "Repeated focus question"
    TaskStageRole.Situation -> "Situation"
    TaskStageRole.Utterance -> "Utterance"
    TaskStageRole.Choice -> "Spoken choice"
}
