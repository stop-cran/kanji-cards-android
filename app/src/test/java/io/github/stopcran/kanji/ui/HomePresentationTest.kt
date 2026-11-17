package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.words.WordStacks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomePresentationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun fullKanjiFocusGuidanceIsAvailableOnDemand() {
        rule.setContent {
            MaterialTheme {
                KanjiFocusGuidance(io.github.stopcran.kanji.data.FocusStatus(size = 10, occupancy = 10))
            }
        }
        rule.onNodeWithText("Kanji focus full").assertIsDisplayed()
        rule.onNodeWithText("Finish your current 10 kanji first", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("About the Kanji focus limit").performClick()
        rule.onNodeWithText("Finish your current 10 kanji first: new kanji come in as they are learned in every mode.")
            .assertIsDisplayed()
    }

    @Test fun kanjiFocusGuidanceIsHiddenWhenTheFocusIsNotFull() {
        rule.setContent {
            MaterialTheme {
                KanjiFocusGuidance(io.github.stopcran.kanji.data.FocusStatus(size = 10, occupancy = 9))
            }
        }
        rule.onNodeWithText("Kanji focus full").assertDoesNotExist()
    }

    @Test fun wordHomeRendersAndDispatchesPreferencesWithoutApplicationOrDatabaseAccess() {
        var accepted = false
        val preferences = HomePreferencesActions({}, {}, { accepted = true }, {}, {})
        val data = HomeData(
            false, "fixture/content", emptyList(),
            StackChoices(emptyList(), Stacks.everything, listOf(WordStacks.n5), WordStacks.n5, 2),
            emptyMap(), HomeGuidance(true),
        )
        rule.setContent { MaterialTheme { WordsPage(preferences, data, {}, {}) { _, _ -> } } }
        rule.onNodeWithText("Add N4 words").assertIsDisplayed().performClick()
        assertTrue(accepted)
    }
}
