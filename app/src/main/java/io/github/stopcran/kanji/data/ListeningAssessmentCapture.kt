package io.github.stopcran.kanji.data

import io.github.stopcran.kanji.core.listening.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

data class ListeningAssessmentPreview(
    val sourceId: String, val branch: String?, val revision: String?, val contentVersion: String?,
    val seed: Long, val includeFamiliar: Boolean, val visualAccess: Boolean, val selection: AssessmentSelection,
    val words: Map<String, Map<String, String>>, val notice: String?,
)

internal class ListeningAssessmentCapture(
    private val db: AppDatabase, private val settings: Settings, private val clock: () -> Instant,
) {
    private val assets = ListeningTaskAssets(db)

    suspend fun preview(seed: Long, familiar: Boolean, visual: Boolean): ListeningAssessmentPreview {
        val source = settings.source.value.id
        val dao = db.listeningTasks()
        val sync = dao.sync(source)
        val exposures = db.listening().exposures(source).associate { it.passageId to it.lastPracticeAtMs }
        val candidates = dao.tasks(source).filter { it.json != null }.map { row ->
            val record = ListeningTaskContent.parse("listening-tasks/${row.id}.json", requireNotNull(row.json))
            require(record.contentHash == row.contentHash && record.task.exposureId == row.exposureId) { "Cached task changed; sync again" }
            val problem = if (!row.ready) row.reason ?: "Task is unavailable" else assetProblem(source, record)
            AssessmentCandidate(record, exposures[record.task.exposureId], problem)
        }
        var selection = ListeningAssessmentBlueprint.select(candidates, clock().toEpochMilli(), seed, familiar, visual)
        val words = selection.items.associate { candidate ->
            val task = candidate.record.task
            val refs = task.vocabulary.mapNotNull { it.word }
            val bodies = db.content().referencedWords(source, refs).associate { it.word to it.body }
            if (!bodies.keys.containsAll(refs)) selection = selection.copy(problems = selection.problems + "${task.id}: a word article is missing.")
            task.id to bodies
        }
        if (sync?.acceptedBranch == null || sync.acceptedContentVersion == null) {
            selection = selection.copy(problems = selection.problems + "No accepted Japanese listening source snapshot.")
        }
        return ListeningAssessmentPreview(source, sync?.acceptedBranch, sync?.acceptedRevision, sync?.acceptedContentVersion,
            seed, familiar, visual, selection, words, sync?.problem)
    }

    private suspend fun assetProblem(source: String, record: ListeningTaskRecord): String? =
        try { assets.check(source, record); null }
        catch (e: IllegalArgumentException) { e.message ?: "Task media is unavailable" }

    suspend fun capture(expected: ListeningAssessmentPreview): String {
        require(expected.sourceId == settings.source.value.id) { "Selected source changed; confirm a fresh assessment preview" }
        db.requireNoOtherListeningActivity(expected.sourceId)
        val current = preview(expected.seed, expected.includeFamiliar, expected.visualAccess)
        require(current == expected) { "Assessment availability changed; confirm a fresh preview" }
        require(current.selection.available) { current.selection.problems.joinToString("\n") }
        val id = "listening-assessment:${UUID.randomUUID()}"
        val now = clock().toEpochMilli()
        val items = current.selection.items.mapIndexed { index, candidate ->
            captureItem(id, index, current, candidate, now)
        }
        val form = AssessmentForm(seed = current.seed, includeFamiliar = current.includeFamiliar, visualAccess = current.visualAccess,
            projectedMs = requireNotNull(current.selection.durationMs), items = items)
        db.listeningTasks().putAssessment(ListeningAssessmentEntity(id, current.sourceId, requireNotNull(current.branch),
            current.revision, requireNotNull(current.contentVersion), form.blueprint, ListeningTaskContent.POLICY_VERSION,
            Json.encodeToString(form), Json.encodeToString(AssessmentState()), now))
        db.reviews().insertSession(StudySessionEntity(id, current.sourceId, LISTENING_STACK, LISTENING_MODE, true, now))
        require(expected.sourceId == settings.source.value.id) { "Selected source changed; confirm a fresh assessment preview" }
        return id
    }

    private suspend fun captureItem(
        parent: String, index: Int, preview: ListeningAssessmentPreview, candidate: AssessmentCandidate, now: Long,
    ): AssessmentItem {
        val record = candidate.record
        val task = record.task
        val id = "$parent:item:$index"
        val example = task.usage == TaskUsage.Example
        val state = ListeningTaskAttempt(stageIndex = if (example) 0 else 1, phase = "assessment")
        db.listeningTasks().putEncounter(ListeningTaskEncounterEntity(id, preview.sourceId, task.id, task.exposureId,
            requireNotNull(preview.branch), preview.revision, requireNotNull(preview.contentVersion), record.json, record.contentHash,
            requireNotNull(record.mediaHash), task.policyVersion, Json.encodeToString(preview.words.getValue(task.id)),
            Json.encodeToString(state), "assessment", parent, now, ListeningAssessmentBlueprint.durationMs(task), newReservation = false))
        db.listeningTasks().pin(record.assetHashes.map { ListeningAssetPinEntity(preview.sourceId, "assessment", parent, it) })
        return AssessmentItem(id, task.id, task.taskType, example, candidate.lastExposureMs != null, record.contentHash, record.mediaHash)
    }
}
