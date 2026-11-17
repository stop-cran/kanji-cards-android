package io.github.stopcran.kanji.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reading_reader_words (sourceId TEXT NOT NULL, textId TEXT NOT NULL, captureId TEXT NOT NULL, " +
                "word TEXT NOT NULL, reading TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, position INTEGER NOT NULL, " +
                "PRIMARY KEY(sourceId, textId, captureId, word))",
        )
        db.query("SELECT sourceId, textId, captureId, length(wordsJson) FROM reading_readers").use { captures ->
            while (captures.moveToNext()) {
                val key = arrayOf(captures.getString(0), captures.getString(1), captures.getString(2))
                val length = captures.getInt(3)
                val json = buildString {
                    // Reading the old aggregate in slices also permits migrating rows larger than a CursorWindow.
                    for (offset in 1..length step 16_384) {
                        db.query(
                            "SELECT substr(wordsJson, ?, 16384) FROM reading_readers WHERE sourceId = ? AND textId = ? AND captureId = ?",
                            arrayOf(offset, key[0], key[1], key[2]),
                        ).use { slice ->
                            check(slice.moveToFirst()) { "Captured Reading vocabulary disappeared during migration" }
                            append(slice.getString(0))
                        }
                    }
                }
                val words = Json.decodeFromString(ListSerializer(ReadingWordSnapshot.serializer()), json)
                words.forEachIndexed { position, word ->
                    db.execSQL(
                        "INSERT INTO reading_reader_words VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(key[0], key[1], key[2], word.word, word.reading, word.title, word.body, position),
                    )
                }
            }
        }
        db.execSQL("UPDATE reading_readers SET wordsJson = '[]'")
    }
}
