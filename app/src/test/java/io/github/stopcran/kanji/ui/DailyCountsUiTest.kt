package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.PacingNote
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w800dp-h1280dp")
    fun homeControlsShareARowWhenTheyFitAndWrapWithoutClippingInEnglishAndRussian() {
        val context = mutableStateOf(localizedContext("en"))
        val width = mutableStateOf(600.dp)
        val scale = mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, scale.value),
            ) {
                MaterialTheme {
                    Column(Modifier.requiredWidth(width.value).testTag("help")) {
                        Page("Home") { HomeHelp(false, {}, {}, {}, {}, allowance(7), allowance(3)) }
                    }
                }
            }
        }
        for (language in listOf("en", "ru")) {
            val localized = localizedContext(language)
            for ((availableWidth, fontScale) in listOf(
                600.dp to 1f, 393.dp to 1f, 312.dp to 1f, 600.dp to 2f, 393.dp to 2f, 312.dp to 2f,
            )) {
                rule.runOnIdle {
                    context.value = localized
                    scale.value = fontScale
                    width.value = availableWidth
                }
                val guide = rule.onNodeWithText(localized.getString(R.string.starting_guide_title))
                val counts = rule.onNodeWithText(localized.getString(R.string.daily_counts_show))
                val guideBounds = guide.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val countsBounds = counts.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val parent = rule.onNodeWithTag("help").fetchSemanticsNode().boundsInRoot
                val fitsOnOneRow = availableWidth == 600.dp || availableWidth == 393.dp && language == "en"
                if (fontScale == 1f && fitsOnOneRow) {
                    assertEquals(guideBounds.top, countsBounds.top, 1f)
                    assertTrue(guideBounds.right <= countsBounds.left)
                }
                if (availableWidth == 312.dp) assertTrue(guideBounds.bottom <= countsBounds.top)
                for (node in listOf(guide, counts)) {
                    val bounds = node.fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.left >= parent.left && bounds.right <= parent.right)
                    assertLabelFits(node)
                }
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun homeHelpExpandsIndependentlyAtFullWidthAndRestoresBothStates() {
        val context = localizedContext("ru")
        val visited = mutableListOf<String>()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f),
            ) {
                val guideVisible = rememberSaveable { mutableStateOf(false) }
                MaterialTheme {
                    Column(
                        Modifier.requiredWidth(280.dp).verticalScroll(rememberScrollState()).testTag("help"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HomeHelp(
                            guideVisible.value, { guideVisible.value = it },
                            { visited += "kana" }, { visited += "words" }, { visited += "grammar" },
                            allowance(7), allowance(3),
                        )
                    }
                }
            }
        }
        fun node(id: Int) = rule.onNodeWithText(context.getString(id))
        val kanaRemaining = context.getString(R.string.kana_allowance_remaining, 7, 10)
        val contentRemaining = context.getString(R.string.content_allowance_remaining, 3, 10)
        node(R.string.daily_counts_show).performClick()
        node(R.string.starting_guide_title).performClick()
        restoration.emulateSavedInstanceStateRestore()
        node(R.string.starting_guide_hide).performScrollTo().assertIsDisplayed()
        node(R.string.daily_counts_hide).performScrollTo().assertIsDisplayed()
        val parentWidth = rule.onNodeWithTag("help").fetchSemanticsNode().boundsInRoot.width
        val guide = node(R.string.starting_guide_choices).performScrollTo()
        val counts = node(R.string.daily_counts_kana).performScrollTo()
        assertEquals(parentWidth, counts.textLayout().size.width.toFloat(), 1f)
        val density = rule.density.density
        assertEquals(parentWidth - 24 * density, guide.textLayout().size.width.toFloat(), 1f)
        rule.onNodeWithText(kanaRemaining).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(contentRemaining).performScrollTo().assertIsDisplayed()
        for (id in listOf(R.string.starting_guide_kana, R.string.starting_guide_words, R.string.starting_guide_grammar)) {
            node(id).performScrollTo().performClick()
        }
        assertEquals(listOf("kana", "words", "grammar"), visited)
        node(R.string.daily_counts_hide).performScrollTo().performClick()
        node(R.string.daily_counts_kana).assertDoesNotExist()
        node(R.string.starting_guide_choices).performScrollTo().assertIsDisplayed()
        node(R.string.daily_counts_show).performScrollTo().performClick()
        node(R.string.starting_guide_hide).performScrollTo().performClick()
        node(R.string.starting_guide_choices).assertDoesNotExist()
        rule.onNodeWithText(kanaRemaining).performScrollTo().assertIsDisplayed()
    }

    private fun localizedContext(language: String): Context {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertLabelFits(node: SemanticsNodeInteraction) {
        val layout = node.textLayout()
        val label = layout.layoutInput.text.text
        assertFalse(label, layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertTrue(label, layout.getLineLeft(line) >= 0f)
            assertTrue(label, layout.getLineRight(line) <= layout.size.width + 1f)
            assertFalse(label, layout.isLineEllipsized(line))
        }
        assertEquals(label, label.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(layout.layoutInput.style.fontSize.value >= 14f)
    }

    private fun allowance(remaining: Int) = NewAllowance(10, remaining, PacingNote.None, 0, 40)
}
