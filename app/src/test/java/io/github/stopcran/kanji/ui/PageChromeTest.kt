package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.stopcran.kanji.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PageChromeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun pageFooterRemainsVisibleWhileLongDetailsScroll() {
        var advances = 0
        rule.setContent {
            MaterialTheme {
                Page("Feedback", footer = {
                    androidx.compose.material3.Button(onClick = { advances++ }) { Text("Continue") }
                }) {
                    repeat(40) { Text("Detailed explanation $it") }
                }
            }
        }
        val action = rule.onNodeWithText("Continue").assertIsDisplayed()
        val position = action.fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Detailed explanation 39").performScrollTo().assertIsDisplayed()
        assertEquals(position, action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        action.performClick()
        rule.runOnIdle { assertEquals(1, advances) }
    }

    @Test
    fun answerFeedbackIsNotColourOnly() {
        rule.setContent {
            AnswerOption("wind", Verdict.Correct)
            AnswerOption("air", Verdict.Wrong)
            AnswerOption("spirit", Verdict.Neutral)
        }
        rule.onNodeWithText("✓ wind").assertIsDisplayed()
        rule.onNodeWithText("✗ air").assertIsDisplayed()
        rule.onNodeWithText("spirit").assertIsDisplayed()
        val context = ApplicationProvider.getApplicationContext<Application>()
        rule.onNodeWithContentDescription(context.getString(R.string.answer_correct_accessibility, "wind")).assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.answer_wrong_accessibility, "air")).assertIsDisplayed()
        rule.onNodeWithContentDescription("spirit").assertIsDisplayed()
    }

    @Test
    fun sessionCounterExplainsQueuedRepeatsOnDemand() {
        rule.setContent {
            MaterialTheme {
                SessionRemaining(3)
            }
        }
        rule.onNodeWithText("Questions left: 3").assertIsDisplayed()
        rule.onNodeWithText("This count includes queued repeats.", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Why can questions repeat?").performClick()
        rule.onNodeWithText("About questions left").assertIsDisplayed()
        rule.onNodeWithText(
            "This count includes queued repeats. A missed question may return after other questions when available, " +
                "so the count can stay the same after you answer.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("About questions left").assertDoesNotExist()
    }

    @Test
    fun dontKnowButtonClicks() {
        var clicks = 0
        rule.setContent { DontKnowButton { clicks++ } }
        rule.onNodeWithText("I don't know").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun pageShowsTitleAndBackNavigates() {
        var backs = 0
        rule.setContent { Page("Cards (3)", onBack = { backs++ }) { androidx.compose.material3.Text("content") } }
        rule.onNodeWithText("Cards (3)").assertIsDisplayed()
        rule.onNodeWithText("content").assertIsDisplayed()
        rule.onNode(hasContentDescription("Back")).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun pageWithoutBackHasNoBackButton() {
        rule.setContent { Page("Home") { androidx.compose.material3.Text("x") } }
        rule.onNode(hasContentDescription("Back")).assertDoesNotExist()
    }
}
