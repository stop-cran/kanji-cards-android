package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.stopcran.kanji.core.content.ContentRejections
import io.github.stopcran.kanji.core.content.ContentType
import io.github.stopcran.kanji.data.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "notnight")
class SyncStatusTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rendersAllTotalsAndOnlyNonzeroRejectionsInRedAcrossRestoration() {
        val result = SyncResult.Updated(10, 20, listOf("A diagnostic"), grammar = 30, listening = 40,
            rejected = ContentRejections(mapOf(ContentType.Grammar to 2, ContentType.Listening to 1, ContentType.Words to 0)))
        val restored = StateRestorationTester(rule)
        lateinit var update: (SyncStatus) -> Unit
        restored.setContent {
            var status by rememberSaveable(stateSaver = SyncStatus.Saver) { mutableStateOf(SyncStatus()) }
            update = { status = it }
            KanjiTheme { Column { SyncStatusText(status) } }
        }
        rule.runOnIdle { update(SyncStatus.from(result)) }
        restored.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("30 grammar exercises", substring = true).assertIsDisplayed()
        rule.onNodeWithText("40 listening packages", substring = true).assertIsDisplayed()
        val rejected = rule.onNodeWithText("Grammar: 2 rejected file(s)", substring = true).assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rejected.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(LightScheme.error, layouts.single().layoutInput.style.color)
        assertTrue("Words:" !in layouts.single().layoutInput.text.text)
        rule.runOnIdle { update(SyncStatus.from(SyncResult.Updated(10, 20, emptyList()))) }
        rule.onNodeWithText("Invalid or unsupported content:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("content issue(s)", substring = true).assertDoesNotExist()
    }

    @Test fun failuresDoNotRenderCandidateTotalsAndInterruptedSyncOffersRetry() {
        val status = SyncStatus.from(SyncResult.Failed("Keeping previous content", false,
            ContentRejections(mapOf(ContentType.Kanji to 1))))
        rule.setContent { KanjiTheme { Column { SyncStatusText(status) } } }
        rule.onNodeWithText("Sync failed:", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Kanji: 1 rejected file(s)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Stored content:", substring = true).assertDoesNotExist()
        assertEquals("Sync interrupted; tap Save & sync to retry.", SyncStatus(SyncStatus.SYNCING).afterRecreation().message)
        assertEquals(status, status.afterRecreation())
        assertEquals(SyncStatus("Up to date"), SyncStatus.from(SyncResult.UpToDate))
    }

    @Test fun retryabilityAndDistinctGuidanceSurviveSavedStateRestoration() {
        val restored = StateRestorationTester(rule)
        lateinit var update: (SyncStatus) -> Unit
        lateinit var read: () -> SyncStatus
        restored.setContent {
            var status by rememberSaveable(stateSaver = SyncStatus.Saver) { mutableStateOf(SyncStatus()) }
            update = { status = it }
            read = { status }
            KanjiTheme { Column { SyncStatusText(status) } }
        }
        for (retryable in listOf(true, false)) {
            rule.runOnIdle { update(SyncStatus.from(SyncResult.Failed("Same failure", retryable))) }
            restored.emulateSavedInstanceStateRestore()
            rule.runOnIdle { assertEquals(retryable, read().retryable) }
            rule.onNodeWithText("Sync failed: Same failure").assertIsDisplayed()
            val hint = if (retryable) "This failure may be temporary." else "Check the error details before retrying."
            rule.onNodeWithText(hint, substring = true).assertIsDisplayed()
        }
        assertEquals(SyncStatus("Old saved failure"), SyncStatus.Saver.restore(listOf("Old saved failure", "", "")))
        rule.runOnIdle { update(SyncStatus.from(SyncResult.UpToDate)) }
        rule.onNodeWithText("Check the error details before retrying.", substring = true).assertDoesNotExist()
    }
}
