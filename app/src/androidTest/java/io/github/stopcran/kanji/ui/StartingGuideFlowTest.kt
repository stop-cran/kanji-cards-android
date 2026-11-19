package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File
import java.util.Locale

class StartingGuideFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun compactHomeHelpAtSystemFontScaleInEnglishAndRussian() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext
        val context = mutableStateOf(application)
        val guideVisible = mutableStateOf(false)
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme {
                    Page("Home") {
                        HomeHelp(guideVisible.value, { guideVisible.value = it }, {}, {}, {}, null, null)
                    }
                }
            }
        }
        val scale = application.resources.configuration.fontScale
        for (language in listOf("en", "ru")) {
            val localized = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            rule.runOnIdle {
                context.value = localized
                guideVisible.value = false
            }
            val guide = rule.onNodeWithText(localized.getString(R.string.starting_guide_title))
            val counts = rule.onNodeWithText(localized.getString(R.string.daily_counts_show))
            val guideBounds = guide.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val countsBounds = counts.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            if (language == "en" && scale == 1f) {
                assertEquals(guideBounds.top, countsBounds.top, 1f)
                assertTrue(guideBounds.right <= countsBounds.left)
            }
            assertTrue(guideBounds.right <= countsBounds.left || guideBounds.bottom <= countsBounds.top)
            for (node in listOf(guide, counts)) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertFalse(layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertTrue(layout.getLineLeft(line) >= 0f)
                    assertTrue(layout.getLineRight(line) <= layout.size.width + 1f)
                    assertFalse(layout.isLineEllipsized(line))
                }
                assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
            }
            rule.waitForIdle()
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(application.cacheDir, "home-help-$language-${scale}x.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                screenshot.recycle()
            }
            counts.performClick()
            guide.performClick()
            rule.onNodeWithText(localized.getString(R.string.starting_guide_choices)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(localized.getString(R.string.daily_counts_kana)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(localized.getString(R.string.daily_counts_hide)).performScrollTo().performClick()
        }
    }

    @Test fun guideCallbacksRetainChoicesAndDismissalSurvivesReconstructionAndSourceChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "starting-guide-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val settings = mutableStateOf(Settings(isolated))
        val source = settings.value.source.value
        settings.value.setWordStack(WordStacks.n4.id)
        settings.value.setGrammarNewScope(source.id, GrammarScope.All)
        val destination = mutableStateOf<String?>(null)
        val visible = mutableStateOf(true)
        val visited = mutableListOf<String>()
        fun open(name: String) { visited += name; destination.value = name }
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            if (visible.value) {
                val saved = rememberSaveableStateHolder()
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    MaterialTheme {
                        val target = destination.value
                        if (target != null) Page(target, onBack = { destination.value = null }) { Text("Callback destination") }
                        else saved.SaveableStateProvider("home") {
                            Page("Home") {
                                val guideVisible by settings.value.startingGuideVisible.collectAsState()
                                HomeHelp(
                                    guideVisible, settings.value::setStartingGuideVisible,
                                    { open("Kana") }, { open("Words") }, { open("Grammar") }, null, null,
                                )
                            }
                        }
                    }
                }
            }
        }
        try {
            rule.onNodeWithText("Practice is not an unscored demo.", substring = true).performScrollTo().assertIsDisplayed()
            listOf("Kana", "Words", "Grammar").forEach { name ->
                rule.onNodeWithText("Open $name").performScrollTo().performClick()
                rule.onNodeWithText(name).assertIsDisplayed()
                assertEquals(WordStacks.n4.id, settings.value.wordStack.value)
                assertEquals(GrammarScope.All, settings.value.grammarFor(source.id).newScope)
                rule.onNodeWithText("← Back").performClick()
            }
            assertEquals(listOf("Kana", "Words", "Grammar"), visited)
            rule.onNodeWithText("Hide guide").performScrollTo().performClick()
            rule.onNodeWithText("Open Kana").assertDoesNotExist()
            rule.runOnIdle {
                settings.value.setSource("https://github.com/example/fork", "main")
                settings.value = Settings(isolated)
            }
            restoration.emulateSavedInstanceStateRestore()
            assertFalse(settings.value.startingGuideVisible.value)
            rule.onNodeWithText("Open Kana").assertDoesNotExist()
            rule.onNodeWithText("Starting guide").performClick()
            rule.onNodeWithText("Open Kana").performScrollTo().assertIsDisplayed()
            assertEquals(WordStacks.n4.id, settings.value.wordStack.value)
            assertEquals(GrammarScope.All, settings.value.grammarFor(source.id).newScope)
        } finally {
            rule.runOnIdle { visible.value = false }
            rule.waitForIdle()
            context.deleteSharedPreferences(preferences)
        }
    }
}
