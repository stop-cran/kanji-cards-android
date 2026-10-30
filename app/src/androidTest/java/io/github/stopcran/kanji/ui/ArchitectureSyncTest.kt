package io.github.stopcran.kanji.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.data.SyncActivity
import io.github.stopcran.kanji.data.SyncCoordinator
import io.github.stopcran.kanji.data.SyncKind
import io.github.stopcran.kanji.data.SyncRequest
import io.github.stopcran.kanji.data.SyncResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ArchitectureSyncTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsSyncSurvivesNavigationAndRealRecreationWithoutRetargetingOrCrossSourceDiagnostics() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("architectureAcceptance") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KanjiApp
        val oldUrl = app.settings.repoUrl
        val oldBranch = app.settings.branch
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val activity = MutableStateFlow<Map<RepoSource, SyncActivity>>(emptyMap())
        val started = CompletableDeferred<SyncRequest>()
        val finish = CompletableDeferred<Unit>()
        var requests = 0
        val updated = SyncResult.Updated(1, 2, listOf("Native diagnostic"), grammar = 3, listening = 4)
        val owner = SyncCoordinator(ownerScope, activity) { request ->
            requests++
            activity.value += request.source to SyncActivity.Running
            started.complete(request)
            finish.await()
            activity.value += request.source to SyncActivity.Finished(updated)
            updated
        }
        val showSettings = mutableStateOf(true)
        fun mount() = rule.activityRule.scenario.onActivity { host ->
            host.setContent {
                KanjiTheme {
                    val saved = rememberSaveableStateHolder()
                    if (showSettings.value) saved.SaveableStateProvider("settings") {
                        SettingsScreen(app, { showSettings.value = false }, {}, owner)
                    } else Page("Away") {
                        TextButton(onClick = { showSettings.value = true }) { Text("Return to settings") }
                    }
                }
            }
        }
        try {
            app.settings.setSource("https://github.com/fixture/architecture-sync", "captured")
            val captured = app.settings.source.value
            mount()
            rule.onNodeWithText("Save & sync").performScrollTo().performClick()
            rule.waitUntil(10_000) { started.isCompleted }
            assertEquals(SyncRequest(captured, SyncKind.Forced), runBlocking { started.await() })
            rule.onNodeWithText("← Back").performClick()
            rule.onNodeWithText("Away").assertIsDisplayed()
            val previous = rule.activity
            rule.activityRule.scenario.recreate()
            mount()
            assertNotSame(previous, rule.activity)
            rule.onNodeWithText("Return to settings").performClick()
            rule.onNodeWithText(SyncStatus.SYNCING).performScrollTo().assertIsDisplayed()
            assertEquals(1, requests)
            rule.runOnIdle { app.settings.setSource("https://github.com/fixture/other-sync", "other") }
            rule.runOnIdle { finish.complete(Unit) }
            rule.waitUntil(10_000) { owner.activity.value[captured] is SyncActivity.Finished }
            rule.onNodeWithText("Sync complete.", substring = true).assertDoesNotExist()
            rule.runOnIdle { app.settings.setSource("https://github.com/fixture/architecture-sync", "captured") }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Sync complete.", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Sync complete.", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Repository URL").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("invalid")) }
            rule.onNodeWithText("Save & sync").performScrollTo().performClick()
            rule.onNodeWithText("Invalid repository URL", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(1, requests)
            rule.activityRule.scenario.recreate()
            mount()
            rule.onNodeWithText("Invalid repository URL", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Sync complete.", substring = true).assertDoesNotExist()
            File(app.getExternalFilesDir(null), "architecture-sync-native.json").writeText(JSONObject()
                .put("api", Build.VERSION.SDK_INT).put("actualActivityRecreation", true)
                .put("capturedSource", captured.id).put("capturedBranch", captured.branch).put("requests", requests)
                .put("continuedWhileScreenAbsent", true).put("sourceScopedDiagnostics", true)
                .put("localValidationSurvivesRecreation", true).toString(2))
        } finally {
            finish.complete(Unit)
            ownerScope.cancel()
            app.settings.setSource(oldUrl, oldBranch)
        }
    }
}
