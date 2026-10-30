package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class DocumentScene(
    val id: String, val image: String, val description: String, val sense: String? = null,
    val quizEligible: Boolean = true, val expectedHash: String? = null,
)

data class DocumentContent(
    val title: String, val body: String, val scenes: List<DocumentScene> = emptyList(), val revision: String? = null,
)

interface DocumentRepository {
    fun observe(sourceId: String, path: String): Flow<DocumentContent?>
    suspend fun image(sourceId: String, scene: DocumentScene): SceneImageResult
}

class RoomDocumentRepository(private val db: AppDatabase) : DocumentRepository {
    override fun observe(sourceId: String, path: String): Flow<DocumentContent?> {
        val dao = db.content()
        val sceneChanges = if (path.startsWith("words/")) {
            dao.observeWordScenes(sourceId, path.substringAfter('/').removeSuffix(".md")).map { Unit }
        } else flowOf(Unit)
        return combine(dao.observeMeta(sourceId), sceneChanges) { _, _ ->
            db.withTransaction { load(sourceId, path) }
        }.distinctUntilChanged()
    }

    private suspend fun load(sourceId: String, path: String): DocumentContent? {
        val dao = db.content()
        val name = path.substringAfter('/').removeSuffix(".md")
        val revision = dao.meta(sourceId)?.let { "${it.contentVersion}:${it.syncedAtMs}" }
        return when (path.substringBefore('/')) {
            "kanji" -> dao.kanjiCard(sourceId, name)?.let { DocumentContent("${it.kanji}  ${it.title}", it.body, revision = revision) }
            "articles" -> dao.article(sourceId, name)?.let { DocumentContent(it.title, it.body, revision = revision) }
            "words" -> dao.word(sourceId, name)?.let { word ->
                val scenes = dao.scenesForWord(sourceId, name).map {
                    DocumentScene(it.id, it.image, it.description, it.sense, it.quizEligible, it.imageHash)
                }.ifEmpty {
                    word.image?.let { listOf(DocumentScene("legacy", it, word.imageScene ?: word.title)) }.orEmpty()
                }
                DocumentContent("${word.word}  ${word.title}", word.body, scenes, revision)
            }
            else -> null
        }
    }

    override suspend fun image(sourceId: String, scene: DocumentScene) =
        db.content().sceneImage(sourceId, scene.image, scene.expectedHash)
}
