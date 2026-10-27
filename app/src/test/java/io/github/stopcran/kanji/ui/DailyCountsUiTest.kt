package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.PacingNote
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DailyCountsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nonzeroAllowanceDoesNotPromiseUnlockedExercisesAndHelpSurvivesRecreation() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                Page("Counts") {
                    DailyCountsHelp(allowance(7), allowance(10))
                    ModeRow("Reading", ModeState.Unavailable("Needs at least two words with kanji")) {}
                }
            }
        }
        rule.onNodeWithText("Kana allowance: 7 of 10 left today").assertDoesNotExist()
        rule.onNodeWithText("Daily counts & shared limits").performClick()
        rule.onNodeWithText("Kana allowance: 7 of 10 left today").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Content allowance: 10 of 10 left today").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("An allowance is a maximum", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("reserve a shared content slot", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Start").performScrollTo().assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Hide daily-count help").performScrollTo().performClick()
        rule.onNodeWithText("Content allowance: 10 of 10 left today").assertDoesNotExist()
    }

    @Test fun zeroAndReducedAllowancesKeepTheirReasonsAndScopesDistinct() {
        val content = mutableStateOf(allowance(0))
        rule.setContent {
            MaterialTheme {
                Page("Counts") { DailyCountsHelp(allowance(7), content.value) }
            }
        }
        rule.onNodeWithText("Daily counts & shared limits").performClick()
        rule.onNodeWithText("Today’s allowance is used or reserved.", substring = true).performScrollTo().assertIsDisplayed()
        rule.runOnIdle { content.value = allowance(0).copy(total = 0) }
        rule.onNodeWithText("New items are turned off", substring = true).performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            content.value = allowance(0).copy(total = 0, note = PacingNote.BacklogPaused, due = 80)
        }
        rule.onNodeWithText("80 reviews are waiting, so new items are paused", substring = true)
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Kana allowance: 7 of 10 left today").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { content.value = allowance(0).copy(total = 0, note = PacingNote.RestDay) }
        rule.onNodeWithText("Rest day in your weekly rhythm", substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun allowance(remaining: Int) = NewAllowance(10, remaining, PacingNote.None, 0, 40)
}
