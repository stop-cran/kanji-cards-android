package io.github.stopcran.kanji.data

import androidx.room.withTransaction
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.core.readingtext.ReadingPackage
import io.github.stopcran.kanji.core.content.ContentParser
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ReadingWordSnapshot(val word: String, val reading: String, val title: String, val body: String)

data class ReadingReader(
    val saved: ReadingReaderEntity,
    val content: ReadingPackage,
    val words: Map<String, ReadingWordSnapshot>,
)

interface TextReadingRepository {
    fun selections(sourceId: String): Flow<List<ReadingWordIntentEntity>>
    suspend fun selectWord(reader: ReadingReader, word: String)
    suspend fun removeWord(reader: ReadingReader, word: String)
    suspend fun open(sourceId: String, textId: String, captureId: String? = null): ReadingReader
    suspend fun position(reader: ReadingReader, section: Int, offset: Int)
    suspend fun furigana(reader: ReadingReader, visible: Boolean)
    suspend fun complete(reader: ReadingReader)
}

class RoomTextReadingRepository(private val db: AppDatabase, private val settings: Settings) : TextReadingRepository {
    override fun selections(sourceId: String) = db.reading().observeWordIntents(sourceId)

    override suspend fun selectWord(reader: ReadingReader, word: String) {
        require(word in reader.words) { "Only a captured canonical word can be selected" }
        db.reading().putWordIntent(ReadingWordIntentEntity(reader.saved.sourceId, word, UUID.randomUUID().toString(), System.currentTimeMillis()))
    }

    override suspend fun removeWord(reader: ReadingReader, word: String) {
        db.reading().removeWordIntent(reader.saved.sourceId, word)
    }

    override suspend fun open(sourceId: String, textId: String, captureId: String?): ReadingReader = db.withTransaction {
        val saved = if (captureId == null) capture(sourceId, textId)
        else requireNotNull(db.reading().reader(sourceId, textId, captureId)) { "Captured reading text is unavailable" }
        val record = ReadingContent.parse("reading/$textId.json", saved.contentJson)
        require(record.hash == saved.contentHash) { "Captured reading text failed verification" }
        record.content.questions.mapNotNull { it.image }.forEach { picture ->
            val image = requireNotNull(db.reading().readerImage(sourceId, textId, saved.captureId, picture.path)) { "Captured reading picture is missing" }
            require(image.sha256 == picture.sha256 && ContentParser.rawSha256(image.bytes) == picture.sha256) { "Captured reading picture failed verification" }
        }
        val words = db.reading().readerWords(sourceId, textId, saved.captureId)
            .associate { it.word to ReadingWordSnapshot(it.word, it.reading, it.title, it.body) }
        ReadingReader(saved, record.content, words)
    }

    private suspend fun capture(sourceId: String, textId: String): ReadingReaderEntity {
        val source = settings.source.value
        require(source.id == sourceId) { "The selected source changed; reopen Reading" }
        val accepted = db.sourceAcceptance().current()?.takeIf { it.sourceId == sourceId }
        require(accepted == null || accepted.branch == source.branch) { "Source replacement is still completing; reopen Reading" }
        val row = requireNotNull(db.reading().text(sourceId, textId)) { "Reading text is unavailable; sync again" }
        val record = ReadingContent.parse("reading/$textId.json", row.json)
        require(record.hash == row.contentHash) { "Reading text failed verification; sync again" }
        val meta = requireNotNull(db.content().meta(sourceId)) { "Reading source has no accepted content" }
        val captureId = ContentParser.sha256("${source.id}\n$textId\n${source.branch}\n${meta.contentVersion}\n${row.contentHash}".toByteArray())
        db.reading().reader(sourceId, textId, captureId)?.let { return it }
        val ids = record.content.sections.flatMap { it.vocabulary }.mapNotNull { it.word }.distinct()
        val words = ids.chunked(900).flatMap { db.content().referencedWords(sourceId, it) }
            .mapIndexed { position, word ->
                ReadingReaderWordEntity(sourceId, textId, captureId, word.word, word.reading, word.title, word.body, position)
            }
        val images = record.content.questions.mapNotNull { it.image }.distinctBy { it.path }.map { picture ->
            val image = requireNotNull(db.reading().image(sourceId, picture.path)) { "Reading picture is unavailable; sync again" }
            require(image.sha256 == picture.sha256 && ContentParser.rawSha256(image.bytes) == picture.sha256 &&
                SceneImages.problem(image.bytes) == null) { "Reading picture failed verification; sync again" }
            ReadingReaderImageEntity(sourceId, textId, captureId, image.path, image.sha256, image.bytes)
        }
        db.reading().putReaderImages(images)
        db.reading().putReaderWords(words)
        return ReadingReaderEntity(
            sourceId, textId, source.branch, meta.contentRevision, meta.contentVersion, row.json, row.contentHash,
            "[]", System.currentTimeMillis(),
            captureId = captureId,
        ).also { db.reading().putReader(it) }
    }

    override suspend fun position(reader: ReadingReader, section: Int, offset: Int) = withContext(NonCancellable) {
        require(section >= 0 && section <= reader.content.sections.size + 1 && offset >= 0) { "Invalid reading position" }
        val saved = reader.saved
        check(db.reading().savePosition(saved.sourceId, saved.textId, saved.captureId, section, offset) == 1) {
            "The captured reading changed before its position was saved"
        }
    }

    override suspend fun furigana(reader: ReadingReader, visible: Boolean) {
        val saved = reader.saved
        check(db.reading().saveFurigana(saved.sourceId, saved.textId, saved.captureId, visible) == 1) { "Captured reading is unavailable" }
    }

    override suspend fun complete(reader: ReadingReader) {
        val saved = reader.saved
        check(db.reading().completeReading(saved.sourceId, saved.textId, saved.captureId, System.currentTimeMillis()) == 1) {
            "Captured reading is unavailable"
        }
    }
}
