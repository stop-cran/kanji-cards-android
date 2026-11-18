package io.github.stopcran.kanji.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.readingtext.ReadingContent
import io.github.stopcran.kanji.core.readingtext.ReadingPackage
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
import org.junit.Rule
import org.junit.Test
import java.io.File

class TextReadingFlowTest {
    @get:Rule val rule = createComposeRule()

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
        val saved = ReadingReaderEntity("fixture/reading", content.id, "main", "fixture", "fixture", json, parsed.hash, "[]", 1)
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
