package io.github.stopcran.kanji.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MarkdownLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tableColumnsStayReadableAtTheActualSystemTextScale() {
        val table = """
            | Expression | A useful literal interpretation | Natural English |
            | --- | --- | --- |
            | 日本語を勉強すること | the activity of studying Japanese | studying Japanese |
            | 窓を開けたまま | in the unchanged state resulting from opening the window | with the window left open |
            | 私が知っているかぎり | within the limits of what I know | as far as I know |
        """.trimIndent()
        rule.setContent { MaterialTheme { Page("Article table", onBack = {}) { MarkdownView(table, "articles/table.md", {}) } } }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Expression").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(!layouts.single().hasVisualOverflow)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "article-table-${context.resources.configuration.fontScale}.png")
        output.outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        rule.onNodeWithText("as far as I know").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("日本語を勉強すること").performScrollTo().assertIsDisplayed()
    }
}
