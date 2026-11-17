package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
