package io.github.stopcran.kanji.data

import androidx.room.Entity

@Entity(tableName = "reading_texts", primaryKeys = ["sourceId", "id"])
data class PreservedReadingText(
    val sourceId: String,
    val id: String,
    val json: String,
    val contentHash: String,
    val title: String,
    val jlpt: Int,
    val position: Int,
)

@Entity(tableName = "reading_images", primaryKeys = ["sourceId", "path"])
class PreservedReadingImage(val sourceId: String, val path: String, val sha256: String, val bytes: ByteArray)

@Entity(tableName = "reading_readers", primaryKeys = ["sourceId", "textId"])
data class PreservedReadingReader(
    val sourceId: String,
    val textId: String,
    val branch: String,
    val revision: String?,
    val contentVersion: String,
    val contentJson: String,
    val contentHash: String,
    val wordsJson: String,
    val openedAtMs: Long,
    val sectionIndex: Int,
    val scrollOffset: Int,
    val furigana: Boolean,
    val completedAtMs: Long?,
)

@Entity(tableName = "reading_reader_images", primaryKeys = ["sourceId", "textId", "path"])
class PreservedReadingReaderImage(
    val sourceId: String,
    val textId: String,
    val path: String,
    val sha256: String,
    val bytes: ByteArray,
)
