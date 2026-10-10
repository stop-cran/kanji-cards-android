package io.github.stopcran.kanji

import android.app.Application
import android.content.Context
import androidx.room.Room
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.MIGRATION_1_2
import io.github.stopcran.kanji.data.MIGRATION_2_3
import io.github.stopcran.kanji.data.MIGRATION_3_4
import io.github.stopcran.kanji.data.ReminderWorker
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncWorker

/** What a ViewModel may use; passed in by a factory so ViewModels never reach for the Application. */
class Services(val db: AppDatabase, val settings: Settings, val context: Context)

class KanjiApp : Application() {
    val db: AppDatabase by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "kanji.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build() }
    val settings: Settings by lazy { Settings(this) }
    val contentSync: ContentSync by lazy { ContentSync(db, settings) }
    val services: Services by lazy { Services(db, settings, this) }

    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedulePeriodic(this)
        if (settings.reminderEnabled.value) ReminderWorker.schedule(this, settings.reminderHour.value, replace = false)
    }
}
