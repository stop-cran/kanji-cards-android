package io.github.stopcran.kanji

import android.app.Application
import android.content.Context
import androidx.room.Room
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.MIGRATION_1_2
import io.github.stopcran.kanji.data.MIGRATION_2_3
import io.github.stopcran.kanji.data.MIGRATION_3_4
import io.github.stopcran.kanji.data.MIGRATION_4_5
import io.github.stopcran.kanji.data.MIGRATION_5_6
import io.github.stopcran.kanji.data.MIGRATION_6_7
import io.github.stopcran.kanji.data.MIGRATION_7_8
import io.github.stopcran.kanji.data.MIGRATION_8_9
import io.github.stopcran.kanji.data.MIGRATION_9_10
import io.github.stopcran.kanji.data.MIGRATION_10_11
import io.github.stopcran.kanji.data.MIGRATION_11_12
import io.github.stopcran.kanji.data.MIGRATION_12_13
import io.github.stopcran.kanji.data.MIGRATION_13_14
import io.github.stopcran.kanji.data.MIGRATION_14_15
import io.github.stopcran.kanji.data.MIGRATION_15_16
import io.github.stopcran.kanji.data.rebuildGrammarReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.github.stopcran.kanji.data.ReminderWorker
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncWorker
import io.github.stopcran.kanji.ui.ArtSet
import io.github.stopcran.kanji.ui.StudySessionRegistry

/** What a ViewModel may use; passed in by a factory so ViewModels never reach for the Application. */
class Services(val db: AppDatabase, val settings: Settings, val context: Context) {
    val studySessions = StudySessionRegistry()
    val listening = io.github.stopcran.kanji.data.ListeningStudy(db, settings)
    val listeningTasks = io.github.stopcran.kanji.data.ListeningTaskStudy(db, settings)
    val listeningAssessments = io.github.stopcran.kanji.data.ListeningAssessmentStudy(db, settings, listeningTasks)
}

class KanjiApp : Application() {
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("GrammarReadiness", "Readiness rebuild failed; retained milestones remain usable.", error)
    })
    val db: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "kanji.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
            )
            .build()
    }
    val settings: Settings by lazy { Settings(this) }
    val contentSync: ContentSync by lazy { ContentSync(db, settings) }
    val services: Services by lazy { Services(db, settings, this) }

    override fun onCreate() {
        super.onCreate()
        ArtSet.choose(settings)
        maintenance.launch {
            settings.source.collectLatest {
                try {
                    db.rebuildGrammarReadiness(it.id)
                } catch (error: IllegalArgumentException) {
                    android.util.Log.e("GrammarReadiness", "Rebuild failed; retry on sync or source selection.", error)
                } catch (error: android.database.sqlite.SQLiteException) {
                    android.util.Log.e("GrammarReadiness", "Rebuild failed; retry on sync or source selection.", error)
                }
            }
        }
        SyncWorker.schedulePeriodic(this)
        if (settings.reminderEnabled.value) ReminderWorker.schedule(this, settings.reminderHour.value, replace = false)
    }
}
