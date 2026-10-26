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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.listening.ListeningAssessmentProfile
import io.github.stopcran.kanji.core.listening.ListeningScope
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.core.words.WordStacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class LevelFilterLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sectionChoicesStayReadableAndSelectableOnANarrowScreen() {
        val samples = listOf(
            "Kanji" to Stacks.available((1..5).map { listOf("jlpt-n$it", "starter") }).map { it.id to it.label },
            "Words" to WordStacks.offered(true, 4).map { it.id to it.label },
            "Counters" to CounterScope.entries.map { it.code to it.label },
            "Grammar" to GrammarScope.entries.map { it.id to it.label },
            "Listening" to ListeningScope.entries.map { it.code to it.label },
            "Diagnostic" to ListeningAssessmentProfile.entries.map { it.id to "N${it.jlpt}" },
        )
        val sample = mutableStateOf(samples.first())
        val selected = mutableStateOf(sample.value.second.first())
        rule.setContent {
            MaterialTheme {
                Page("Level selectors", onBack = {}) {
                    Column(Modifier.requiredWidth(280.dp)) {
                        Text(sample.value.first)
                        if (sample.value.first == "Grammar") {
                            GrammarScopeChips(GrammarScope.fromId(selected.value.first)) { selected.value = it.id to it.label }
                        } else {
                            LevelFilter(sample.value.second, selected.value, { selected.value = it }, { it.second })
                        }
                        Text("Selected: ${selected.value.first}")
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (group in samples) {
            rule.runOnIdle { sample.value = group; selected.value = group.second.first() }
            val trailing = if (group.first == "Grammar") group.second.single { it.second == "Unclassified" } else group.second.last()
            for (choice in listOf(group.second.single { it.second == "N4" }, trailing)) {
                rule.onNodeWithText(choice.second).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
                rule.runOnIdle { assertEquals(choice.first, selected.value.first) }
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(choice.second, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.single().lineCount)
                val layout = layouts.single()
                assertEquals(choice.second.length, layout.getLineEnd(0))
                // Scrolled text has an unbounded paragraph width; check glyphs against its actual rendered size.
                for (index in choice.second.indices) {
                    val glyph = layout.getBoundingBox(index)
                    assertTrue("${group.first}: ${choice.second}",
                        glyph.left >= 0 && glyph.right <= layout.size.width &&
                            glyph.top >= 0 && glyph.bottom <= layout.size.height)
                }
            }
            val output = File(context.getExternalFilesDir(null),
                "level-filter-${group.first}-${context.resources.configuration.fontScale}.png")
            output.outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
