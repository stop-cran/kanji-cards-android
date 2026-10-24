package io.github.stopcran.kanji.ui

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.*
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import io.github.stopcran.kanji.core.content.ParsedContent
import io.github.stopcran.kanji.core.listening.ListeningTaskCatalog
import io.github.stopcran.kanji.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListeningAssessmentLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun registeredNoisyReceiverIrreversiblyPausesWithoutInventingExposure() {
        val context = rule.activity.applicationContext
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val services = Services(db, Settings(context), context)
        val assets = listOf(taskTone(), assessmentAsset("listening-short-tone.ogg"), assessmentAsset("listening-test-picture.webp"))
            .associateBy(ContentParser::rawSha256)
        val id = runBlocking {
            db.withTransaction {
                db.importListeningTasks(ParsedContent(Manifest(1, "noisy-fixture", emptyMap()), emptyList(), emptyList(), emptyList(),
                    emptyMap(), emptyList(), listeningTasks = ListeningTaskCatalog(records = assessmentPool().map { it.record }, assets = assets)),
                    services.settings.source.value, "a".repeat(40))
            }
            services.listeningAssessments.start(services.listeningAssessments.preview(42))
        }
        val vm = ListeningAssessmentViewModel(services, id)
        val returned = mutableStateOf(false)
        rule.setContent { KanjiTheme {
            if (returned.value) Text("Returned") else ListeningAssessmentView(vm, { returned.value = true }) { _, _ -> }
        } }
        try {
            await { vm.ready && !vm.busy }
            rule.onNodeWithText("Begin section").performScrollTo().assertIsDisplayed()
            // Robolectric delivers the protected system event; this is not a physical unplug test.
            rule.runOnIdle { context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
            await { vm.snapshot?.state?.paused == true && !vm.busy }
            assertTrue(vm.snapshot!!.state.interrupted)
            assertTrue(vm.snapshot!!.state.interruptions.any { "Headphones" in it })
            assertTrue(runBlocking { db.listeningTasks().allExposures().isEmpty() })
            assertTrue(runBlocking { db.reviews().allLog().isEmpty() })
            rule.onNodeWithText("Continue as interrupted practice").performScrollTo().assertIsDisplayed()
        } finally {
            rule.runOnIdle { vm.pause("Fixture cleanup", leave = true); returned.value = true }
            await { !vm.busy && !vm.playing }
            db.close()
        }
    }

    private fun await(condition: () -> Boolean) {
        rule.waitUntil(15_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
    }
}
