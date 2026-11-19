package io.github.stopcran.kanji.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.core.readingtext.ReadingPackage
import io.github.stopcran.kanji.core.readingtext.ReadingSection
import io.github.stopcran.kanji.core.readingtext.ReadingOccurrence
import io.github.stopcran.kanji.data.ReadingReader
import io.github.stopcran.kanji.data.ReadingReaderEntity
import io.github.stopcran.kanji.data.ReadingWordSnapshot
import io.github.stopcran.kanji.data.ReadingWordIntentEntity
import io.github.stopcran.kanji.data.TextReadingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class TextReadingFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun mixedKanjiRubyAlignsWithPlainTextAndKeepsWholeWordLookups() {
        val section = ReadingSection("mixed", "ご飯を食べ物と小さい村に行きました。", listOf(
            ReadingOccurrence(0, 2, "ご飯", "ごはん", "ご飯", "rice"),
            ReadingOccurrence(3, 6, "食べ物", "たべもの", "食べ物", "food"),
            ReadingOccurrence(7, 11, "小さい村", "ちいさいむら", "小さい村", "small village"),
            ReadingOccurrence(12, 17, "行きました", "いきました", "行く", "went"),
        ))
        var picked: ReadingOccurrence? = null
        var readings by mutableStateOf(true)
        var expanded by mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                Page("Reading alignment fixture", {}) {
                    ReadingSectionText(if (expanded) section.copy(text = section.text.repeat(100)) else section, readings) { picked = it }
                }
            }
        }
        val paragraph = rule.onNodeWithTag("reading-section:mixed")
        val layouts = mutableListOf<TextLayoutResult>()
        paragraph.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val origin = paragraph.fetchSemanticsNode().positionInRoot
        val scale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        screenshot("reading-aligned-ruby-$scale")
        val bases = listOf("飯", "食", "物", "小", "村", "行")
        for ((index, base) in bases.withIndex()) {
            val expected = section.vocabulary[listOf(0, 1, 1, 2, 2, 3)[index]]
            val tag = listOf("reading-word:mixed:0", "reading-word:mixed:3", "reading-word:mixed:3:2",
                "reading-word:mixed:7", "reading-word:mixed:7:2", "reading-word:mixed:12")[index]
            val child = rule.onNode(hasText(base) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            val childLayouts = mutableListOf<TextLayoutResult>()
            child.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(childLayouts) }
            val node = child.fetchSemanticsNode()
            val line = layout.getLineForOffset(layout.layoutInput.placeholders[index].start)
            assertEquals("Ruby and plain text baseline: $base", origin.y + layout.getLineBaseline(line),
                node.positionInRoot.y + childLayouts.single().lastBaseline, 1f)
            val paintedBottom = node.positionInRoot.y + childLayouts.single().size.height
            assertTrue("Painted base must fit paragraph line", paintedBottom <= origin.y + layout.getLineBottom(line) + 1f)
            assertTrue("Painted base must fit full paragraph", paintedBottom <= origin.y + layout.size.height + 1f)
            assertTrue("Ruby must fit paragraph line", layout.placeholderRects[index]!!.top >= layout.getLineTop(line) - 1f)
            val hitBounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
            rule.runOnIdle { assertEquals("Direct callback must match its rendered word", expected, picked); picked = null }
            child.performTouchInput { click(Offset(childLayouts.single().size.width / 2f, childLayouts.single().firstBaseline / 2f)) }
            rule.runOnIdle {
                assertEquals("Painted base $base at ${node.positionInRoot} / ${childLayouts.single().size}; tag=$hitBounds", expected, picked)
                picked = null
            }
            rule.onNodeWithTag(tag).performClick()
            rule.runOnIdle {
                assertEquals("Ruby click for $base; tag=$hitBounds; child=${node.positionInRoot}", expected, picked)
                picked = null
            }
            child.performTouchInput {
                click(Offset(childLayouts.single().size.width / 2f, childLayouts.single().lastBaseline + 1f))
            }
            rule.runOnIdle { assertEquals("Painted base descent must be tappable", expected, picked); picked = null }
        }
        for ((offset, expected) in listOf(0 to "ご飯", 4 to "食べ物", 8 to "小さい村", 13 to "行きました")) {
            paragraph.performTouchInput { click(layout.getBoundingBox(offset).center) }
            rule.runOnIdle { assertEquals(expected, picked?.text); picked = null }
        }
        for (offset in listOf(2, 6, 11, 17)) {
            paragraph.performTouchInput { click(layout.getBoundingBox(offset).center) }
            rule.runOnIdle { assertEquals("Unmarked text must not open a wrapped neighbour's link", null, picked) }
        }
        rule.runOnIdle { readings = false }
        screenshot("reading-hidden-ruby-$scale")
        val hiddenLayouts = mutableListOf<TextLayoutResult>()
        paragraph.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(hiddenLayouts) }
        for ((offset, expected) in listOf(1 to "ご飯", 4 to "食べ物", 8 to "小さい村", 13 to "行きました")) {
            paragraph.performTouchInput { click(hiddenLayouts.single().getBoundingBox(offset).center) }
            rule.runOnIdle { assertEquals("Hidden-ruby word tap", expected, picked?.text); picked = null }
        }
        rule.runOnIdle { expanded = true }
        val before = paragraph.fetchSemanticsNode().positionInRoot.y
        paragraph.performTouchInput { swipeUp() }
        val after = paragraph.fetchSemanticsNode().positionInRoot.y
        rule.runOnIdle {
            assertTrue("A real text swipe must scroll the reader", after < before - 20f)
            assertEquals("A scroll gesture must not open a lookup", null, picked)
        }
    }

    @Test fun capturedArticleAndReaderPositionSurviveALoadingRestorationFrame() {
        ReadingNativeProcessEvidence.capture("saved-state-restoration")
        val initialLoad = CompletableDeferred<Unit>()
        val repository = MemoryRepository(reader()).apply { openGate = initialLoad }
        var current: TextReadingViewModel? = null
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                val vm = remember { TextReadingViewModel(repository, "fixture/reading", "reading-fixture-peach") }
                SideEffect { current = vm }
                TextReadingScreen(vm, {}, {})
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(TextReadingUi.Loading, current?.ui)
            initialLoad.complete(Unit)
        }
        awaitReaderReady { current?.ui }
        rule.onNodeWithTag("reading-sections").performScrollToIndex(3)
        rule.waitUntil { repository.saved.saved.sectionIndex == 3 }
        rule.onNodeWithTag("reading-word:section-a:3").performClick()
        rule.onNodeWithText("peach").assertExists()
        rule.onNodeWithText("Read word article").performClick()
        rule.onNodeWithText("Captured peach article").assertExists()
        val gate = CompletableDeferred<Unit>()
        rule.runOnIdle { repository.openGate = gate }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(TextReadingUi.Loading, current?.ui) }
        rule.onNodeWithText("Captured peach article").assertDoesNotExist()
        rule.runOnIdle { gate.complete(Unit) }
        awaitReaderReady { current?.ui }
        rule.onNodeWithText("Captured peach article").assertExists()
        screenshot("reading-restored-article")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithTag("reading-word:section-a:3").assertExists()
        rule.runOnIdle { assertEquals(3, repository.saved.saved.sectionIndex) }
        rule.onNodeWithText("Hide readings").performClick()
        rule.waitUntil(20_000) {
            rule.runOnIdle {
                (current?.ui as? TextReadingUi.Ready)?.reader?.saved?.furigana == false &&
                    !repository.saved.saved.furigana
            }
        }
        rule.onNodeWithText("Show readings").assertExists()
        rule.runOnIdle { assertFalse(repository.saved.saved.furigana) }
        screenshot("reading-restored-position")
    }

    private fun awaitReaderReady(current: () -> TextReadingUi?) {
        rule.waitUntil(20_000) {
            rule.runOnIdle {
                val state = current()
                assertFalse("Captured Reading fixture became unavailable", state is TextReadingUi.Unavailable)
                state is TextReadingUi.Ready
            }
        }
        rule.waitForIdle()
    }

    private fun reader(): ReadingReader {
        val raw = requireNotNull(javaClass.getResourceAsStream("/reading-fixture.json")).bufferedReader().use { it.readText() }
        val fixture = ReadingContent.parse("reading/reading-fixture-peach.json", raw).content
        val content = fixture.copy(sections = fixture.sections + ('a'..'x').map {
            fixture.sections.first().copy(id = "section-$it", text = fixture.sections.first().text.repeat(12))
        })
        val json = Json.encodeToString(ReadingPackage.serializer(), content)
        val parsed = ReadingContent.parse("reading/${content.id}.json", json)
        val saved = ReadingReaderEntity("fixture/reading", content.id, "main", "fixture", "fixture", json, parsed.hash, "[]", 1,
            furigana = true)
        return ReadingReader(saved, content, mapOf("桃" to ReadingWordSnapshot("桃", "もも", "peach", "Captured peach article")))
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private class MemoryRepository(var saved: ReadingReader) : TextReadingRepository {
        var openGate: CompletableDeferred<Unit>? = null
        private val intents = MutableStateFlow<List<ReadingWordIntentEntity>>(emptyList())
        override fun selections(sourceId: String) = intents
        override suspend fun selectWord(reader: ReadingReader, word: String) {
            intents.value = listOf(ReadingWordIntentEntity(reader.saved.sourceId, word, "token", 1))
        }
        override suspend fun removeWord(reader: ReadingReader, word: String) { intents.value = emptyList() }
        override suspend fun open(sourceId: String, textId: String, captureId: String?): ReadingReader {
            openGate?.await()
            return saved
        }
        override suspend fun position(reader: ReadingReader, section: Int, offset: Int) {
            saved = saved.copy(saved = saved.saved.copy(sectionIndex = section, scrollOffset = offset))
        }
        override suspend fun furigana(reader: ReadingReader, visible: Boolean) {
            saved = saved.copy(saved = saved.saved.copy(furigana = visible))
        }
        override suspend fun complete(reader: ReadingReader) = Unit
    }
}
