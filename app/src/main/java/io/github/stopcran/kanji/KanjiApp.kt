package io.github.stopcran.kanji

import android.app.Application
import androidx.room.Room
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncWorker

class KanjiApp : Application() {
    val db: AppDatabase by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "kanji.db").build() }
    val settings: Settings by lazy { Settings(this) }
    val contentSync: ContentSync by lazy { ContentSync(db) }

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(this)
    }
}
