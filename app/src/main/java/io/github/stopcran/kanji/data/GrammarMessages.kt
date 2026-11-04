package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.grammar.GrammarScope

enum class GrammarMessageId {
    FocusNoEligible,
    FocusNoAllowance,
    FocusReviewOrChange,
    FocusContinue,
    FocusReset,
    SyncNeeded,
    ContentProblem,
    ReadinessRebuilding,
    ReadinessProblem,
    NoExercises,
    NoDue,
    NoUnseenForScope,
    TargetUnavailable,
    PracticeDataUnavailable,
    FocusChangeFailed,
    FocusUnavailable,
}

data class GrammarMessage(
    val id: GrammarMessageId,
    val label: String? = null,
    val scope: GrammarScope? = null,
)

enum class GrammarPreviewNoticeId {
    Interrupted,
    OverrideConsentExpired,
    PreviewChanged,
    QueueEmpty,
}

data class GrammarPreviewNotice(val id: GrammarPreviewNoticeId)
