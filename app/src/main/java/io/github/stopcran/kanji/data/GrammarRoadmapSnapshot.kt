package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GrammarEvidence
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarMapGroup
import io.github.stopcran.kanji.core.grammar.GrammarPractice
import io.github.stopcran.kanji.core.grammar.GrammarScope
import java.time.Instant

data class GrammarRoadmapSnapshot(
    val study: GrammarStudy,
    val curriculum: GrammarCurriculumState,
    val groups: Map<String, GrammarMapGroup>,
)

suspend fun AppDatabase.grammarRoadmap(settings: Settings, source: RepoSource, now: Instant): GrammarRoadmapSnapshot = withTransaction {
    require(settings.source.value == source) { "The selected source changed. Return to Grammar to open its roadmap." }
    requireNotNull(content().meta(source.id)) { "Sync this source before opening its roadmap." }
    val sets = grammarSets(source.id)
    GrammarPractice.validateSnapshot(sets)
    val study = grammarStudy(settings, source.id, now)
    val exercises = sets.flatMap { it.exercises }.associateBy { it.id }
    val events = reviews().grammarEvidence(source.id, now.minus(GrammarEvidence.WINDOW).toEpochMilli())
    val articles = sets.flatMap { set -> set.exercises.map { it.id to (set.article to set.title) } }.toMap()
    val groups = GrammarFocus.groups(sets).associate { group ->
        group.id to GrammarMapGroup(
            group, study.availability.getValue(group.id),
            GrammarEvidence.evaluate(group, exercises, study.states, events, now),
            group.authored && group.id in study.readiness.readyGroups,
            if (!study.preferences.levelsReady && study.preferences.browseScope != GrammarScope.All) null else
                group.members.count { study.preferences.browseScope.matches(exercises.getValue(it.exerciseId).jlpt, cumulative = false) },
            group.members.map { articles.getValue(it.exerciseId) }.toMap(),
            study.readiness.problem,
        )
    }
    val result = GrammarRoadmapSnapshot(study, grammarCurriculum(settings, source.id), groups)
    require(settings.source.value == source) { "The selected source changed. Return to Grammar to open its roadmap." }
    result
}
