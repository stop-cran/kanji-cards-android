package io.github.stopcran.kanji.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log

internal const val READING_22_IDENTITY = "bb4bd6ed0ae5d551d26ef9d695be95b2"
internal const val QUEUE_22_IDENTITY = "c15c3d949cdafd3afc602dec4a00e947"
internal const val READING_23_A_IDENTITY = "a784ae19620761de81f6d70367395585"
internal const val READING_23_B_IDENTITY = "51223af7be5dcde053f7428cc9952cc1"
internal const val READING_24_IDENTITY = "ecadf2177c260909da03914c88b08759"

internal fun supportedHistoricalDatabase(version: Int, identity: String?): Boolean = when (version) {
    22 -> identity == READING_22_IDENTITY || identity == QUEUE_22_IDENTITY
    23 -> identity == READING_23_A_IDENTITY || identity == READING_23_B_IDENTITY
    24 -> identity == READING_24_IDENTITY
    25 -> identity == READING_22_IDENTITY
    else -> false
}

private const val MAX_SCHEMA_CHARS = 10_000
private val preserveCorruptDatabase = DatabaseErrorHandler {
    Log.e("DatabaseCompatibility", "Database corruption detected; preserving database files.")
}

internal class UnsupportedDatabaseSchemaException(message: String) : IllegalStateException(message)

/** Check historical branch versions before Room opens the file for migration. */
internal fun checkDatabaseCompatibility(context: Context) {
    val file = context.getDatabasePath("kanji.db")
    if (!file.isFile) return
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, preserveCorruptDatabase).use { db ->
        val version = db.rawQuery("PRAGMA user_version", null).use {
            check(it.moveToFirst()) { "Could not read database version" }
            it.getInt(0)
        }
        if (version !in 22..25) return
        val identity = databaseIdentity(db)
        if (!supportedHistoricalDatabase(version, identity)) {
            throw UnsupportedDatabaseSchemaException(
                "Unsupported historical database schema. Database was not migrated.\n" + schemaMetadata(db, version, identity),
            )
        }
    }
}

internal fun readDatabaseSchemaMetadata(context: Context): String? {
    val file = context.getDatabasePath("kanji.db")
    if (!file.isFile) return null
    return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, preserveCorruptDatabase).use { db ->
        val version = db.rawQuery("PRAGMA user_version", null).use {
            check(it.moveToFirst()) { "Could not read database version" }
            it.getInt(0)
        }
        schemaMetadata(db, version, databaseIdentity(db))
    }
}

private fun databaseIdentity(db: SQLiteDatabase): String? {
    val present = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'room_master_table'",
        null,
    ).use { it.moveToFirst() }
    if (!present) return null
    val columns = db.rawQuery("PRAGMA table_info(room_master_table)", null).use {
        buildSet {
            while (it.moveToNext()) add(it.getString(1))
        }
    }
    if (!columns.containsAll(listOf("id", "identity_hash"))) return null
    return db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}

private fun schemaMetadata(db: SQLiteDatabase, version: Int, identity: String?): String {
    val result = StringBuilder("Database user_version: $version\nRoom identity_hash: ${identity ?: "(missing)"}\n")
    db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name LIMIT 128", null).use { tables ->
        while (tables.moveToNext() && result.length < MAX_SCHEMA_CHARS) {
            val name = tables.getString(0)
            result.append("Table: ").append(name.take(256)).append("\nColumns:")
            if (name.length <= 256) appendColumns(db, name, result)
            result.append('\n')
        }
    }
    if (result.length >= MAX_SCHEMA_CHARS) result.append("[Schema metadata truncated]\n")
    return result.toString()
}

private fun appendColumns(db: SQLiteDatabase, name: String, result: StringBuilder) {
    db.rawQuery("SELECT name, type FROM pragma_table_info(?) ORDER BY cid LIMIT 256", arrayOf(name)).use { columns ->
        while (columns.moveToNext() && result.length < MAX_SCHEMA_CHARS) {
            result.append(' ').append(columns.getString(0).take(256))
                .append(':').append(columns.getString(1).orEmpty().take(128))
        }
    }
}
