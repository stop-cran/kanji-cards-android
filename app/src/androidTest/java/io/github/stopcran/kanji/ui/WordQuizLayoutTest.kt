package io.github.stopcran.kanji.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.data.WordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class WordQuizLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun kyorokyoroFitsQuestionAndFeedbackWithNativeFonts() {
        val word = WordEntity("fixture/words", "きょろきょろ", "きょろきょろ", "looking around restlessly", "wago", "", "", "")
        val font = mutableStateOf(KanjiFont.Gothic)
        rule.setContent {
            MaterialTheme {
                Page("Word layout", onBack = {}) {
                    Column(Modifier.requiredWidth(280.dp)) {
                        Text("Question")
                        WordQuizPrompt(word, WordDirection.JpToEn, font.value, true)
                        Text("What does it mean?")
                        Text("Answer")
                        WordQuizWord(AnnotatedString(word.word), font.value, 56.sp)
                        Text("looking around restlessly")
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (face in KanjiFont.entries) {
            rule.runOnIdle { font.value = face }
            for (index in 0..1) {
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onAllNodesWithText(word.word)[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("$face, prompt $index", 1, layout.lineCount)
                assertTrue(!layout.hasVisualOverflow)
                assertEquals(word.word.length, layout.getLineEnd(0))
                assertTrue(layout.getBoundingBox(word.word.lastIndex).right <= layout.size.width)
            }
            val output = File(context.getExternalFilesDir(null), "word-layout-${face.name}-${context.resources.configuration.fontScale}.png")
            output.outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
