package io.github.stopcran.kanji.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.github.stopcran.kanji.core.content.ContentRejections
import io.github.stopcran.kanji.core.content.ContentType
import io.github.stopcran.kanji.data.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyncStatusNativeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun syncResultsRemainReadableAndRedInBothThemes() {
        val dark = mutableStateOf(false)
        val result = SyncResult.Updated(200, 3000, listOf("A malformed file was skipped."),
            grammar = 400, listening = 64, rejected = ContentRejections(ContentType.entries.associateWith { 2 }, 1))
        val status = mutableStateOf(SyncStatus.from(result))
        rule.setContent {
            MaterialTheme(colorScheme = if (dark.value) DarkScheme else LightScheme) {
                Page("Sync result", onBack = {}) { SyncStatusText(status.value) }
            }
        }
        for (isDark in listOf(false, true)) {
            rule.runOnIdle { dark.value = isDark }
            rule.onNodeWithText("400 grammar exercises", substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("64 listening packages", substring = true).assertIsDisplayed()
            val errors = rule.onNodeWithText("Listening: 2 rejected file(s)", substring = true).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            errors.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(if (isDark) DarkScheme.error else LightScheme.error, layouts.single().layoutInput.style.color)
        }
        rule.runOnIdle { status.value = SyncStatus.from(result.copy(problems = emptyList(), rejected = ContentRejections())) }
        rule.onNodeWithText("Invalid or unsupported content:", substring = true).assertDoesNotExist()
    }
}
