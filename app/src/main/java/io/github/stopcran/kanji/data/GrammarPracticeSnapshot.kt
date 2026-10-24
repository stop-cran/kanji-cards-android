package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.grammar.GrammarPractice
import io.github.stopcran.kanji.core.grammar.GrammarPracticePreview
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import java.time.Instant

data class GrammarPracticeSnapshot(val study: GrammarStudy, val preview: GrammarPracticePreview)

suspend fun AppDatabase.grammarPractice(
    settings: Settings,
    target: GrammarPracticeTarget,
    now: Instant,
): GrammarPracticeSnapshot = withTransaction {
    require(target.matches(settings.source.value)) {
        "The original content source or branch is no longer selected. Return to Grammar and choose a topic again."
    }
    val meta = requireNotNull(content().meta(target.sourceId)) { "No accepted content snapshot is available. Sync the source first." }
    // Validate before grammarStudy builds virtual groups from the cached relationships.
    GrammarPractice.validateSnapshot(grammarSets(target.sourceId))
    val study = grammarStudy(settings, target.sourceId, now, extra = true)
    val curriculum = if (target.kind == GrammarTargetKind.Chapter) grammarCurriculum(settings, target.sourceId)
        else GrammarCurriculumState.Absent
    val preview = GrammarPractice.preview(
        target, study.sets, curriculum, study.availability, meta.contentVersion, study.preferences.parserEpoch,
        study.preferences.newScope, settings.practiceMinimum(), study.readiness.problem, study.notice,
    )
    require(target.matches(settings.source.value)) { "The selected source changed while preparing practice. Choose a topic again." }
    GrammarPracticeSnapshot(study, preview)
}
