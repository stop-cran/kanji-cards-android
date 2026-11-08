package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.core.unlock.UnlockRule
import java.util.Locale
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

    @Test fun wordPrerequisitesStayLocalizedAcrossLocaleChanges() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val english = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val russian = english.createConfigurationContext(Configuration(english.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        })
        val context = mutableStateOf<android.content.Context>(english)
        val direction = mutableStateOf(WordDirection.EnToJp)
        val modes = mapOf(
            WordDirection.EnToJp to HomeMode.WordEn,
            WordDirection.Reading to HomeMode.WordReading,
            WordDirection.Type to HomeMode.WordType,
        )
        val data = HomeData(
            false, "fixture/content", emptyList(),
            StackChoices(emptyList(), Stacks.everything, listOf(WordStacks.n5), WordStacks.n5, 13),
            modes.values.associateWith { ModeState.Locked("Unlocks as you learn words") },
            HomeGuidance(false),
        )
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme { WordModeRow(data, "Mode", modes.getValue(direction.value), direction.value) {} }
            }
        }
        for (localeContext in listOf(english, russian, english)) {
            for (value in modes.keys) {
                rule.runOnIdle {
                    context.value = localeContext
                    direction.value = value
                }
                val gateLabel = if (value.gate == WordDirection.JpToEn) {
                    R.string.mode_japanese_english
                } else R.string.mode_english_japanese
                rule.onNodeWithText(
                    localeContext.getString(R.string.word_unlock_practise, localeContext.getString(gateLabel)),
                ).assertIsDisplayed()
                rule.onNodeWithText(localeContext.getString(R.string.mode_status_unavailable)).assertDoesNotExist()
            }
        }
    }

    @Test fun meaningUnlockReasonsKeepTheirPrerequisitesAcrossLocaleChanges() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val english = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val russian = english.createConfigurationContext(Configuration(english.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        })
        val context = mutableStateOf<android.content.Context>(english)
        val unlock = mutableStateOf(UnlockRule.NextDay)
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme { ModeRow("Mode", ModeState.Locked(unlock.value.hint)) {} }
            }
        }
        for (localeContext in listOf(english, russian, english)) {
            for (value in UnlockRule.entries) {
                rule.runOnIdle {
                    context.value = localeContext
                    unlock.value = value
                }
                rule.onNodeWithText(localeContext.getString(unlockRuleHintResource(value))).assertIsDisplayed()
                rule.onNodeWithText(localeContext.getString(R.string.mode_status_unavailable)).assertDoesNotExist()
            }
        }
    }

    @Test fun fullKanjiFocusGuidanceIsAvailableOnDemand() {
        rule.setContent {
            MaterialTheme {
                KanjiFocusGuidance(io.github.stopcran.kanji.data.FocusStatus(size = 10, occupancy = 10))
            }
        }
        rule.onNodeWithText("Kanji focus full").assertIsDisplayed()
        rule.onNodeWithText("Finish your current 10 kanji first", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("About the Kanji focus limit").performClick()
        rule.onNodeWithText("Finish your current 10 kanji first: new kanji come in as required practice is completed.")
            .assertIsDisplayed()
    }

    @Test fun fullFocusLockedReasonStaysLocalizedAcrossLocaleChanges() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val english = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val russian = english.createConfigurationContext(Configuration(english.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        })
        val context = mutableStateOf<android.content.Context>(english)
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme { ModeRow("Mode", ModeState.Locked("Kanji focus full")) {} }
            }
        }
        for (localeContext in listOf(english, russian, english)) {
            rule.runOnIdle { context.value = localeContext }
            rule.onNodeWithText(localeContext.getString(R.string.kanji_focus_full)).assertIsDisplayed()
            rule.onNodeWithText(localeContext.getString(R.string.mode_status_unavailable)).assertDoesNotExist()
        }
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
