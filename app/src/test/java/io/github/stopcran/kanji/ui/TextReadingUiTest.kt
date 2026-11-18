package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.data.ReadingReader
import io.github.stopcran.kanji.data.ReadingReaderEntity
import io.github.stopcran.kanji.data.ReadingWordSnapshot
import io.github.stopcran.kanji.data.ReadingWordIntentEntity
import io.github.stopcran.kanji.data.TextReadingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextReadingUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun annotatedWordWithoutCardStillPeeksAndFuriganaTogglePersists() {
        val repository = FakeReadingRepository(reader(false))
        rule.setContent {
            MaterialTheme {
                val vm = remember { TextReadingViewModel(repository, "owner/repo", "reading-fixture-peach") }
                TextReadingScreen(vm, {}, {})
            }
        }
        rule.onNodeWithTag("reading-word:river:3").performClick()
        rule.onNodeWithText("peach").assertExists()
        rule.onNodeWithText("This word has a local explanation but no linked word card.").assertExists()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Hide readings").performClick()
        rule.onNodeWithText("Show readings").assertExists()
        rule.runOnIdle { assertEquals(false, repository.visible) }
    }

    @Test fun capturedWordArticleRetainsItsBodyAndResolvesFurtherLinks() {
        val repository = FakeReadingRepository(reader(true))
        var opened: String? = null
        rule.setContent {
            MaterialTheme {
                val vm = remember { TextReadingViewModel(repository, "owner/repo", "reading-fixture-peach") }
                TextReadingScreen(vm, {}, { opened = it })
            }
        }
        rule.onNodeWithTag("reading-word:river:3").performClick()
        rule.onNodeWithText("Read word article").performClick()
        rule.onNodeWithText("Captured peach article").assertExists()
        rule.onNodeWithText("More about peaches", substring = true).clickFirstCharacter()
        rule.runOnIdle { assertEquals("articles/peaches.md", opened) }
    }

    @Test fun studyNextSelectionIsVisibleAndCanBeCancelledWithoutChoosingADeck() {
        val repository = FakeReadingRepository(reader(true))
        rule.setContent {
            MaterialTheme {
                val vm = remember { TextReadingViewModel(repository, "owner/repo", "reading-fixture-peach") }
                TextReadingScreen(vm, {}, {})
            }
        }
        rule.onNodeWithTag("reading-word:river:3").performClick()
        rule.onNodeWithText("Study next").performClick()
        rule.onNodeWithText("Cancel selection").assertExists()
        rule.runOnIdle { assertEquals("桃", repository.intents.value.single().word) }
        rule.onNodeWithText("Cancel selection").performClick()
        rule.onNodeWithText("Study next").assertExists()
        rule.runOnIdle { assertTrue(repository.intents.value.isEmpty()) }
    }

    @Test fun rubyAnnotationRemainsTappableAtNarrowWidth() {
        val section = reader(false).content.sections.first()
        var picked = false
        rule.setContent {
            MaterialTheme {
                Column(Modifier.requiredWidth(240.dp)) {
                    ReadingSectionText(section, true) { picked = true }
                }
            }
        }
        rule.onNodeWithTag("reading-word:river:3").performClick()
        rule.runOnIdle { assertTrue(picked) }
    }

    @Test fun longRubyWrapsAtEnlargedFontInsteadOfSilentlyDisappearing() {
        val original = reader(false).content.sections.first()
        val reading = "もも".repeat(20)
        val section = original.copy(vocabulary = original.vocabulary.map { it.copy(reading = reading) })
        var picked = false
        rule.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Column(Modifier.requiredWidth(160.dp)) { ReadingSectionText(section, true) { picked = true } }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(reading, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        rule.onNodeWithTag("reading-word:river:3").performClick()
        rule.runOnIdle { assertTrue(picked) }
    }

    @Test
    @Config(sdk = [35])
    fun rubyAndPlainTextKeepTheirMeasuredSpaceAtNormalAndLargeSystemFonts() {
        val original = reader(false).content.sections.first()
        val word = original.vocabulary.single()
        val text = "あ${word.text}い${word.text}う${word.text}え${word.text}お"
        val section = original.copy(text = text, vocabulary = (0..3).map {
            word.copy(start = 1 + it * (word.text.length + 1), end = 2 + it * (word.text.length + 1))
        })
        var scale by androidx.compose.runtime.mutableStateOf(1f)
        var visible by androidx.compose.runtime.mutableStateOf(true)
        var longReading by androidx.compose.runtime.mutableStateOf(false)
        var picked = false
        rule.setContent {
            val context = LocalContext.current
            val density = Density(context.createConfigurationContext(Configuration(LocalConfiguration.current).apply {
                fontScale = scale
            }))
            CompositionLocalProvider(LocalDensity provides density) {
                MaterialTheme {
                    Column(Modifier.requiredWidth(240.dp).verticalScroll(rememberScrollState())) {
                        ReadingSectionText(section.copy(vocabulary = section.vocabulary.mapIndexed { index, occurrence ->
                            occurrence.copy(reading = if (longReading && index % 2 == 1) "もも".repeat(20) else word.reading)
                        }), visible) { picked = true }
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 2f)) {
            for (long in listOf(false, true)) {
                rule.runOnIdle { scale = fontScale; visible = true; longReading = long }
                assertInlineGeometry(section.id, section.vocabulary.map { it.start },
                    section.vocabulary.indices.map { if (long && it % 2 == 1) "もも".repeat(20) else word.reading }, word.text)
                rule.onNodeWithTag("reading-word:${section.id}:1").performClick()
                rule.runOnIdle { assertTrue(picked); picked = false; visible = false }
                val node = rule.onNodeWithTag("reading-section:${section.id}")
                val layout = node.textLayout()
                assertTextFits(layout)
                assertLinesDoNotOverlap(layout)
                node.performTouchInput { click(layout.getBoundingBox(1).center) }
                rule.runOnIdle { assertTrue(picked); picked = false }
            }
        }
    }

    @Test
    @Config(sdk = [35])
    fun readerHeaderKeepsFullTitleAndFuriganaControlAtNormalAndLargeSystemFonts() {
        var scale by androidx.compose.runtime.mutableStateOf(1f)
        val repository = FakeReadingRepository(reader(false).let {
            it.copy(content = it.content.copy(title = "A small peach"))
        })
        rule.setContent {
            val context = LocalContext.current
            val density = Density(context.createConfigurationContext(Configuration(LocalConfiguration.current).apply {
                fontScale = scale
            }))
            CompositionLocalProvider(LocalDensity provides density) {
                MaterialTheme {
                    val vm = remember { TextReadingViewModel(repository, "owner/repo", "reading-fixture-peach") }
                    Box(Modifier.requiredWidth(320.dp)) { TextReadingScreen(vm, {}, {}) }
                }
            }
        }
        for (fontScale in listOf(1f, 2f)) {
            rule.runOnIdle { scale = fontScale }
            for (label in listOf("Hide readings", "Show readings")) {
                val title = rule.onNodeWithText("A small peach")
                val action = rule.onNodeWithText(label)
                assertTrue("Header must not split words into a narrow vertical column", title.textLayout().lineCount <= 2)
                assertTextFits(title.textLayout())
                val titleBounds = title.fetchSemanticsNode().boundsInRoot
                val actionBounds = action.fetchSemanticsNode().boundsInRoot
                assertFalse("Reader control overlaps its title", titleBounds.overlaps(actionBounds))
                val actionLayout = action.textLayout()
                assertTextFits(actionLayout)
                action.performClick()
            }
        }
    }

    private fun assertInlineGeometry(sectionId: String, starts: List<Int>, readings: List<String>, base: String) {
        val node = rule.onNodeWithTag("reading-section:$sectionId")
        val layout = node.textLayout()
        val origin = node.fetchSemanticsNode().positionInRoot
        assertTextFits(layout)
        val placeholders = layout.placeholderRects.map { requireNotNull(it) }
        val characters = layout.layoutInput.text.indices.mapNotNull {
            if (layout.layoutInput.placeholders.any { range -> it in range.start until range.end }) null else layout.getBoundingBox(it)
        }.filter { it.width > 0f && it.height > 0f }
        for ((index, placeholder) in placeholders.withIndex()) {
            assertTrue("Ruby exceeds paragraph width", placeholder.left >= -1f && placeholder.right <= layout.size.width + 1f)
            assertTrue("Ruby exceeds paragraph height", placeholder.top >= -1f && placeholder.bottom <= layout.size.height + 1f)
            for (other in characters + placeholders.filter { it != placeholder }) {
                assertFalse("Ruby overlaps adjacent text: $placeholder / $other", placeholder.inset(1f).overlaps(other.inset(1f)))
            }
            val reserved = placeholder.translate(origin)
            var widestText = 0f
            var wrapped = false
            for (text in listOf(readings[index], base)) {
                val child = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag("reading-word:$sectionId:${starts[index]}")),
                    useUnmergedTree = true)
                val semantics = child.fetchSemanticsNode()
                val bounds = Rect(semantics.positionInRoot, semantics.size.toSize())
                assertTrue("Ruby child escapes reserved placeholder: $bounds / $reserved",
                    bounds.left >= reserved.left - 1f && bounds.right <= reserved.right + 1f &&
                        bounds.top >= reserved.top - 1f && bounds.bottom <= reserved.bottom + 1f)
                val childLayout = child.textLayout()
                assertTextFits(childLayout)
                wrapped = wrapped || childLayout.lineCount > 1
                widestText = maxOf(widestText, (0 until childLayout.lineCount).maxOf {
                    childLayout.getLineRight(it) - childLayout.getLineLeft(it)
                })
            }
            if (!wrapped) assertTrue("Ruby reserve inflates measured text: $placeholder / $widestText",
                placeholder.width <= widestText + 2f)
        }
        assertLinesDoNotOverlap(layout)
    }

    private fun assertLinesDoNotOverlap(layout: TextLayoutResult) {
        for (line in 1 until layout.lineCount) {
            assertTrue("Text lines overlap", layout.getLineTop(line) >= layout.getLineBottom(line - 1) - 1f)
        }

    }

    private fun layoutDescription(layout: TextLayoutResult) =
        "size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
            "lines=${layout.lineCount}, density=${layout.layoutInput.density}, style=${layout.layoutInput.style}"

    private fun assertTextFits(layout: TextLayoutResult) {
        assertFalse("Text lines were omitted", layout.multiParagraph.didExceedMaxLines)
        assertEquals("The full authored text must be laid out", layout.layoutInput.text.length,
            layout.getLineEnd(layout.lineCount - 1))
        for (line in 0 until layout.lineCount) {
            assertFalse("Text was ellipsized", layout.isLineEllipsized(line))
            assertTrue("Rendered text exceeds width: ${layoutDescription(layout)}",
                layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("Rendered text exceeds height: ${layoutDescription(layout)}",
                layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    private fun Rect.inset(pixels: Float) = Rect(left + pixels, top + pixels, right - pixels, bottom - pixels)

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    @Test fun kanaOnlyAnnotationRemainsTappableWithoutRuby() {
        val section = reader(false).content.sections[1]
        var picked = false
        rule.setContent {
            MaterialTheme {
                Column(Modifier.requiredWidth(240.dp)) {
                    ReadingSectionText(section, true) { picked = true }
                }
            }
        }
        rule.onNodeWithTag("reading-section:home").clickFirstCharacter()
        rule.runOnIdle { assertTrue(picked) }
    }

    @Test fun overlappingWritesPreservePositionFuriganaAndCompletionTogether() {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeReadingRepository(reader(false), gate)
        lateinit var vm: TextReadingViewModel
        rule.setContent {
            MaterialTheme {
                vm = remember { TextReadingViewModel(repository, "owner/repo", "reading-fixture-peach") }
                TextReadingScreen(vm, {}, {})
            }
        }
        rule.runOnIdle {
            vm.toggleFurigana()
            vm.position(1, 48)
            vm.complete()
        }
        rule.runOnIdle { gate.complete(Unit) }
        rule.runOnIdle {
            val saved = (vm.ui as TextReadingUi.Ready).reader.saved
            assertFalse(saved.furigana)
            assertEquals(1, saved.sectionIndex)
            assertEquals(48, saved.scrollOffset)
            assertNotNull(saved.completedAtMs)
        }
    }

    private fun SemanticsNodeInteraction.clickFirstCharacter() {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val position = layouts.single().getBoundingBox(0).center
        performTouchInput { click(position) }
    }

    private fun reader(withWord: Boolean): ReadingReader {
        val raw = requireNotNull(javaClass.getResource("/reading-fixture.json")).readText()
        val parsed = ReadingContent.parse("reading/reading-fixture-peach.json", raw)
        val saved = ReadingReaderEntity("owner/repo", parsed.content.id, "main", "revision", "version",
            raw, parsed.hash, "[]", 1)
        val words = if (withWord) mapOf("桃" to ReadingWordSnapshot("桃", "もも", "peach",
            "Captured peach article\n\n[More about peaches](../articles/peaches.md)")) else emptyMap()
        return ReadingReader(saved, parsed.content, words)
    }

    private class FakeReadingRepository(
        private val reader: ReadingReader,
        private val furiganaGate: CompletableDeferred<Unit>? = null,
    ) : TextReadingRepository {
        var visible: Boolean? = null
        val intents = MutableStateFlow<List<ReadingWordIntentEntity>>(emptyList())
        override fun selections(sourceId: String) = intents
        override suspend fun selectWord(reader: ReadingReader, word: String) {
            intents.value = listOf(ReadingWordIntentEntity(reader.saved.sourceId, word, "token", 1))
        }
        override suspend fun removeWord(reader: ReadingReader, word: String) { intents.value = emptyList() }
        override suspend fun open(sourceId: String, textId: String, captureId: String?) = reader
        override suspend fun position(reader: ReadingReader, section: Int, offset: Int) = Unit
        override suspend fun furigana(reader: ReadingReader, visible: Boolean) {
            furiganaGate?.await()
            this.visible = visible
        }
        override suspend fun complete(reader: ReadingReader) = Unit
    }
}
