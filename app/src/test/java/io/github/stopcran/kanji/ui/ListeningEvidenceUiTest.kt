package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.stopcran.kanji.core.listening.ListeningTurn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ListeningEvidenceUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun evidenceShowsOnlyReferencedCapturedTurnsAndHandlesMissingIds() {
        val turns = listOf(
            ListeningTurn("t1", "a", "Captured quotation.", "Captured translation."),
            ListeningTurn("t2", "b", "Unreferenced quotation.", "Unreferenced translation."),
        )
        rule.setContent { MaterialTheme {
            Page("Feedback") { ListeningPassageEvidence(turns, listOf("t1", "t1", "missing")) }
        } }
        rule.onNodeWithText("Evidence: t1").assertIsDisplayed()
        rule.onNodeWithText("Captured quotation.").assertIsDisplayed()
        rule.onNodeWithText("Captured translation.").assertIsDisplayed()
        rule.onNodeWithText("Unreferenced quotation.").assertDoesNotExist()
        rule.onNodeWithText("Evidence: missing").assertIsDisplayed()
        rule.onNodeWithText("This transcript reference is unavailable.").assertIsDisplayed()
    }
}
