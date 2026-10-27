package io.github.stopcran.kanji.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.ArticleEntity
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WordBrowserFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun phoneSearchIncludesOutsideDeckWordsAndSurvivesArticleBack() = fixture { fixture ->
        assertTrue(fixture.context.resources.configuration.screenWidthDp < 840)
        awaitText("All words (25)")
        rule.onNode(hasSetTextAction()).performClick().performTextInput("outside")
        awaitText("All words (24 of 25)")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("語23"))
        rule.onNodeWithText("語23").assertIsDisplayed().performClick()
        awaitText("Outside document 23.")
        rule.onNodeWithContentDescription("Back").performClick()
        awaitText("All words (24 of 25)")
        rule.onNode(hasSetTextAction()).assertTextContains("outside")
        rule.onNodeWithText("語23").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextReplacement("no-such-reference")
        awaitText("All words (0 of 25)")
        rule.onNodeWithText("No words match.").assertIsDisplayed()
        rule.onNodeWithText("Clear").performClick()
        awaitText("All words (25)")
        assertEquals(WordStacks.n5.id, fixture.app.settings.wordStack.value)
        runBlocking { fixture.app.db.content().clearWords(fixture.source) }
        awaitText("All words (0)")
        rule.onNodeWithText("No words match.").assertDoesNotExist()
    }

    @Test fun wideSelectionAndQuerySurviveNestedReferenceBack() = fixture { fixture ->
        assertTrue("Run at a real wide viewport", fixture.context.resources.configuration.screenWidthDp >= 840)
        awaitText("All words (25)")
        rule.onNode(hasSetTextAction()).performClick().performTextInput("outside")
        rule.onNode(hasSetTextAction()).performImeAction()
        awaitText("All words (24 of 25)")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("語23"))
        rule.onNodeWithText("語23").performClick()
        awaitText("Outside document 23.")
        rule.onNodeWithText("All words (24 of 25)").assertIsDisplayed()
        rule.onNodeWithText("Open nested").performClick()
        awaitText("Nested reference preserved.")
        rule.onNodeWithContentDescription("Back").performClick()
        awaitText("Outside document 23.")
        rule.onNode(hasSetTextAction()).assertTextContains("outside")
        rule.onNodeWithText("語23").assertIsDisplayed()
        assertEquals(WordStacks.n5.id, fixture.app.settings.wordStack.value)
    }

    private fun awaitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun fixture(exercise: (Fixture) -> Unit) {
        Fixture().use { fixture ->
            fixture.seed()
            val visible = mutableStateOf(true)
            val routes = mutableStateListOf("browser")
            rule.setContent {
                if (visible.value) MaterialTheme {
                    val saved = rememberSaveableStateHolder()
                    val route = routes.last()
                    fun pop() {
                        saved.removeState(route)
                        routes.removeAt(routes.lastIndex)
                    }
                    saved.SaveableStateProvider(route) {
                        if (route == "browser") {
                            WordCardsScreen(fixture.app, {}, { routes += "words/$it.md" }, { routes += it })
                        } else DocScreen(fixture.app, route, { pop() }, { routes += it })
                    }
                }
            }
            try {
                exercise(fixture)
            } finally {
                rule.runOnIdle { visible.value = false }
                rule.waitForIdle()
            }
        }
    }

    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as KanjiApp
        private val originalUrl = app.settings.repoUrl
        private val originalBranch = app.settings.branch
        private val originalStack = app.settings.wordStack.value
        val source = "fixture/word-browser-${UUID.randomUUID()}"

        fun seed() {
            app.settings.setSource("https://github.com/$source", "main")
            app.settings.setWordStack(WordStacks.n5.id)
            runBlocking {
                app.db.content().insertWords(listOf(
                    WordEntity(source, "内", "うち", "Inside reference", "wago", "内", "", "Inside document.", jlpt = 5),
                ) + (0..23).map { index ->
                    WordEntity(source, "語${index.toString().padStart(2, '0')}", "ご", "Outside reference $index",
                        "wago", "語", "", "Outside document $index.\n\n[Open nested](../articles/reference.md)", jlpt = 1)
                })
                app.db.content().insertArticles(listOf(ArticleEntity(
                    source, "reference", "Nested reference", "Nested reference preserved.",
                )))
            }
        }

        override fun close() {
            try {
                app.settings.setSource(originalUrl, originalBranch)
                app.settings.setWordStack(originalStack)
            } finally {
                runBlocking {
                    app.db.content().clearWords(source)
                    app.db.content().clearArticles(source)
                }
            }
        }
    }
}
