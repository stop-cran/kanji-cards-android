package io.github.stopcran.kanji.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Room updates user_version and identity only after the entire migration chain.
        when (db.version) {
            in 1..21 -> Unit
            22 -> requireReadingIdentity(db, 22)
            else -> requireReadingIdentity(db, 25)
        }
        MIGRATION_22_23.migrate(db)
        MIGRATION_23_24.migrate(db)
    }
}

val MIGRATION_24_26 = object : Migration(24, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        requireReadingIdentity(db, 24)
    }
}

val MIGRATION_23_26 = object : Migration(23, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (requireReadingIdentity(db, 23) == READING_23_A_IDENTITY) {
            db.execSQL("ALTER TABLE sync_meta ADD COLUMN contentRevision TEXT")
            createReadingQuizTables(db)
        }
        MIGRATION_23_24.migrate(db)
    }
}

private fun requireReadingIdentity(db: SupportSQLiteDatabase, version: Int): String {
    val identity = db.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    check(supportedHistoricalDatabase(version, identity)) { "Unsupported version $version database identity; migration refused" }
    return checkNotNull(identity)
}

internal fun createReadingQuizTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TABLE reading_progress (sourceId TEXT NOT NULL, textId TEXT NOT NULL, unlockedTier INTEGER NOT NULL, " +
            "PRIMARY KEY(sourceId, textId))",
    )
    db.execSQL(
        "CREATE TABLE reading_quizzes (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, textId TEXT NOT NULL, " +
            "captureId TEXT NOT NULL, planJson TEXT NOT NULL, answersJson TEXT NOT NULL, startedAtMs INTEGER NOT NULL, newReservation INTEGER NOT NULL, " +
            "submittedAtMs INTEGER, resultJson TEXT, reviewLogId INTEGER, closedAtMs INTEGER)",
    )
}
