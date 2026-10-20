package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.core.familiarEvents
import io.github.stopcran.kanji.core.recommending
import io.github.stopcran.kanji.core.grammar.GrammarCursor
import io.github.stopcran.kanji.core.grammar.GrammarFocus
import io.github.stopcran.kanji.core.grammar.GrammarFocusInput
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compose UI behaviour that does not need a database: answer feedback, article toggle, page chrome, link routing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ComposeUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun grammarRecommendationReadoutExplainsItsAuthoredLinkAndSourceEvidenceNotTheNewGroupsHistory() {
        val now = Instant.parse("2026-10-18T12:00:00Z")
        val source = grammarFixture("foundation", examples = 2).recommending("beta" to 3)
        val events = familiarEvents(source, now)
        val input = GrammarFocusInput(
            "owner/repo", listOf(source, grammarFixture("beta")),
            events.associate { it.exerciseId to SrsState(CardPhase.Review) }, events, GrammarScope.All, 10, now, GrammarCursor(),
        )
        val candidate = GrammarFocus.decide(input).current!!
        rule.setContent {
            androidx.compose.foundation.layout.Column { GrammarRecommendationReadout(candidate) }
        }
        rule.onNodeWithText("Group: beta. Recommendation priority: 3/3 (maximum, not sum).").assertIsDisplayed()
        rule.onNodeWithText("From foundation (foundation), importance 3: Compare foundation with beta.").assertIsDisplayed()
        rule.onNodeWithText("Supported source facet a: 2 positive contexts, 1 typed, 24h span within 21 days.").assertIsDisplayed()
        rule.onNodeWithText("Supported source facet b: 2 positive contexts, 1 typed, 24h span within 21 days.").assertIsDisplayed()
    }

    @Test
    fun answerFeedbackIsNotColourOnly() {
        rule.setContent {
            AnswerOption("wind", Verdict.Correct)
            AnswerOption("air", Verdict.Wrong)
            AnswerOption("spirit", Verdict.Neutral)
        }
        rule.onNodeWithText("✓ wind").assertIsDisplayed()
        rule.onNodeWithText("✗ air").assertIsDisplayed()
        rule.onNodeWithText("spirit").assertIsDisplayed()
        rule.onNodeWithContentDescription("Correct answer: wind").assertIsDisplayed()
        rule.onNodeWithContentDescription("Your answer, wrong: air").assertIsDisplayed()
        rule.onNodeWithContentDescription("spirit").assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun pictureChoicesExposeScenesButNotJapaneseAnswersUntilChecked() {
        val words = listOf("correct", "wrong").map {
            io.github.stopcran.kanji.core.words.WordCard(it, it, "Meaning $it", "wago", emptyList())
        }
        val scenes = words.mapIndexed { index, word ->
            io.github.stopcran.kanji.core.words.PictureScene(
                word.word,
                io.github.stopcran.kanji.core.content.WordScene("scene", "Usage", "images/${word.word}.webp", "Visible scene $index", true),
                "hash-$index",
            )
        }
        val question = io.github.stopcran.kanji.core.words.PictureQuestions.build(words.first(), words, scenes, kotlin.random.Random(1))!!
        val images = scenes.mapIndexed { index, scene ->
            val decoded = io.github.stopcran.kanji.data.SceneImages.decode(
                io.github.stopcran.kanji.data.sceneWebp(index),
            ) as io.github.stopcran.kanji.data.SceneImageResult.Ready
            scene.scene.image to decoded.bitmap.asImageBitmap()
        }.toMap()
        val picture = WordPictureUi(question, images)
        val picked = androidx.compose.runtime.mutableStateOf<String?>(null)
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                PictureChoices(picture, picked.value, if (picked.value == null) { { picked.value = it } } else null)
            }
        }
        rule.onNodeWithText("correct").assertDoesNotExist()
        rule.onNodeWithText("wrong").assertDoesNotExist()
        rule.onNodeWithContentDescription("Visible scene 0", useUnmergedTree = true).assertExists()
        val index = question.options.indexOfFirst { it.word == "wrong" } + 1
        rule.onNodeWithText("Picture $index").performClick()
        assertEquals("wrong", picked.value)
        rule.onNodeWithText("Correct: correct").assertIsDisplayed()
        rule.onNodeWithText("Your choice: wrong").assertIsDisplayed()
    }

    @Test
    fun dontKnowButtonClicks() {
        var clicks = 0
        rule.setContent { DontKnowButton { clicks++ } }
        rule.onNodeWithText("I don't know").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun articleStartsCollapsedAfterAMissAndOpensOnTap() {
        rule.setContent { ArticleSection(openByDefault = false, key = "q1", body = "Body text here", path = "kanji/x.md", onOpenDoc = {}) }
        rule.onNodeWithText("Body text here").assertDoesNotExist()
        rule.onNodeWithText("Read the article").performClick()
        rule.onNodeWithText("Body text here").assertIsDisplayed()
    }

    @Test
    fun articleIsOpenByDefaultAfterACorrectAnswer() {
        rule.setContent { ArticleSection(openByDefault = true, key = "q2", body = "Open body", path = "kanji/x.md", onOpenDoc = {}) }
        rule.onNodeWithText("Open body").assertIsDisplayed()
        rule.onNodeWithText("Read the article").assertDoesNotExist()
    }

    @Test
    fun pageShowsTitleAndBackNavigates() {
        var backs = 0
        rule.setContent { Page("Cards (3)", onBack = { backs++ }) { androidx.compose.material3.Text("content") } }
        rule.onNodeWithText("Cards (3)").assertIsDisplayed()
        rule.onNodeWithText("content").assertIsDisplayed()
        rule.onNode(hasContentDescription("Back")).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun pageWithoutBackHasNoBackButton() {
        rule.setContent { Page("Home") { androidx.compose.material3.Text("x") } }
        rule.onNode(hasContentDescription("Back")).assertDoesNotExist()
    }

    @Test
    fun markdownRendersHeadingAndText() {
        rule.setContent { Page("t") { MarkdownView("# Title\n\nSome *body* text.", "kanji/x.md", {}) } }
        rule.onNodeWithText("Title").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTextContains("body").isNotEmpty())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextContains(s: String) =
        onAllNodes(androidx.compose.ui.test.hasText(s, substring = true)).fetchSemanticsNodes()
}
