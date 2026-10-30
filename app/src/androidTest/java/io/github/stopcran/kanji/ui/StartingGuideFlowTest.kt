package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class StartingGuideFlowTest {
    @get:Rule val rule = createComposeRule()

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
                                StartingGuide(guideVisible, settings.value::setStartingGuideVisible, { open("Kana") }, { open("Words") }, { open("Grammar") })
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
