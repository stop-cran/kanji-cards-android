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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

data class GrammarRoadmapSnapshot(
    val study: GrammarStudy,
    val curriculum: GrammarCurriculumState,
    val groups: Map<String, GrammarMapGroup>,
) {
    val groupsBySet: Map<String, List<GrammarMapGroup>> = run {
        val owners = study.sets.flatMap { set -> set.exercises.map { it.id to set.id } }.toMap()
        groups.values.groupBy { owners.getValue(it.group.members.first().exerciseId) }
    }
}

internal fun AppDatabase.grammarChanges(sourceId: String): Flow<Unit> {
    val changes = combine(
        content().observeMeta(sourceId),
        reviews().observeStates(sourceId, GRAMMAR_STACK, "Grammar"),
        reviews().observeGrammarFocus(sourceId),
        reviews().observeGrammarReadiness(sourceId),
        reviews().observeGrammarReadinessStatus(sourceId),
    ) { _, _, _, _, _ -> Unit }
    val clock = flow { while (true) { emit(Unit); delay(60_000) } }
    return changes.combine(clock) { _, _ -> Unit }
}

suspend fun AppDatabase.grammarRoadmap(settings: Settings, source: RepoSource, now: Instant): GrammarRoadmapSnapshot = withTransaction {
    require(settings.source.value == source) { "The selected source changed. Return to Grammar to open its roadmap." }
    requireNotNull(content().meta(source.id)) { "Sync this source before opening its roadmap." }
    val sets = grammarSets(source.id)
    GrammarPractice.validateSnapshot(sets)
    val study = grammarStudy(settings, source.id, now)
    val result = grammarRoadmapSnapshot(source.id, study, grammarCurriculum(settings, source.id), now)
    require(settings.source.value == source) { "The selected source changed. Return to Grammar to open its roadmap." }
    result
}

internal suspend fun AppDatabase.grammarRoadmapSnapshot(
    sourceId: String,
    study: GrammarStudy,
    curriculum: GrammarCurriculumState,
    now: Instant,
): GrammarRoadmapSnapshot {
    val sets = study.sets
    val exercises = sets.flatMap { it.exercises }.associateBy { it.id }
    val events = reviews().grammarEvidence(sourceId, now.minus(GrammarEvidence.WINDOW).toEpochMilli())
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
    return GrammarRoadmapSnapshot(study, curriculum, groups)
}
