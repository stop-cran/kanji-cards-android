package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.srs.ProgressCount
import io.github.stopcran.kanji.core.srs.StudyProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LevelFilterProgressTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun chipsExposeExactProgressAndSelectedSkillBreakdown() {
        val selected = mutableStateOf("N5")
        val progress = mapOf(
            "N5" to StudyProgress(
                mapOf("Meaning" to ProgressCount(1, 2), "Drawing" to ProgressCount(0, 2)),
            ),
            "N4" to StudyProgress(emptyMap()),
        )
        rule.setContent {
            MaterialTheme {
                LevelFilter(
                    choices = listOf("N5", "N4"),
                    selected = selected.value,
                    onSelect = { selected.value = it },
                    label = { it },
                    progress = { progress.getValue(it) },
                )
            }
        }

        rule.onNodeWithContentDescription("N5, 25% solid progress, 1 of 4 skill-item entries", useUnmergedTree = true)
            .assertExists()
        rule.onNodeWithText("Solid progress: 25% (1 of 4 skill-item entries)").assertExists()
        rule.onNodeWithText("Meaning: 50% (1 of 2)").assertExists()
        rule.onNodeWithText("Drawing: 0% (0 of 2)").assertExists()

        rule.onNodeWithText("N4").performClick().assertIsSelected()
        rule.onNodeWithText("N4, no level-specific progress available", useUnmergedTree = true).assertExists()
        rule.runOnIdle { assertEquals("N4", selected.value) }
    }

    @Test
    fun kanaScriptSelectorShowsProgressAndAccessibleSelection() {
        val overview = KanaOverview(emptyMap(), null, Instant.parse("2026-01-01T00:00:00Z"))
        val hiragana = overview.progress(KanaScript.Hiragana).total
        val description = "Hiragana, ${hiragana.percent}% solid progress, ${hiragana.solid} of ${hiragana.total} skill-item entries"
        rule.setContent {
            MaterialTheme {
                KanaPage(overview, {}, { _, _, _ -> })
            }
        }

        rule.onNodeWithContentDescription(description).assertIsSelected()
        rule.onNodeWithText(
            "Solid progress: ${hiragana.percent}% (${hiragana.solid} of ${hiragana.total} skill-item entries)",
        ).assertExists()
        rule.onNodeWithText("Katakana").performClick().assertIsSelected()
    }
}
