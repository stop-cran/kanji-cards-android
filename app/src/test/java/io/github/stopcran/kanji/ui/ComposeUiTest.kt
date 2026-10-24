package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordKanjiHelp
import io.github.stopcran.kanji.core.words.WordOption
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.ListeningOverview
import io.github.stopcran.kanji.data.ListeningEncounterEntity
import io.github.stopcran.kanji.core.listening.ListeningAllowance
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.srs.PacingNote
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
    fun targetedPreviewRequiresALabeledOptInAndDropsItForAChangedSnapshot() {
        val sets = listOf(grammarFixture("foundation", examples = 2), grammarFixture("topic", examples = 2, requires = listOf("foundation")))
        val now = Instant.parse("2026-10-24T12:00:00Z")
        val preview = io.github.stopcran.kanji.core.grammar.GrammarPractice.preview(
            io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget(
                "owner/repo", "main", io.github.stopcran.kanji.core.grammar.GrammarTargetKind.Group, "topic",
            ),
            sets, io.github.stopcran.kanji.core.content.GrammarCurriculumState.Absent,
            io.github.stopcran.kanji.core.grammar.GrammarAvailability.evaluate(
                GrammarFocus.groups(sets), sets.flatMap { it.exercises }.associateBy { it.id },
                emptyMap(), GrammarScope.N5, true, emptySet(), 0, now,
            ),
            "v1", 6, GrammarScope.N5, 10,
        )
        val state = androidx.compose.runtime.mutableStateOf(GrammarUi.Preview(preview))
        var approval: Boolean? = null
        rule.setContent { Page("Practice", onBack = {}) { GrammarPracticePreviewContent(state.value, "session") { approval = it } } }
        val consent = "Include the 4 restricted new exercises described above for this session only."
        rule.onNodeWithText("Start").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(consent).performScrollTo().assertIsOff().performClick().assertIsOn()
        rule.onNodeWithText("Start with these exceptions").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(true, approval)
            state.value = GrammarUi.Preview(preview.copy(contentVersion = "v2"), "Review changed material.")
        }
        rule.onNodeWithText(consent).performScrollTo().assertIsOff()
        rule.onNodeWithText("Start").performScrollTo().assertIsNotEnabled()
    }

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
    @Config(qualifiers = "notnight")
    fun grammarMistakesAndGiveUpHighlightBothCorrectChoicesWithExplicitMarkers() {
        val exercise = grammarFixture().exercises.first().copy(
            additionalCorrectOptions = listOf(io.github.stopcran.kanji.core.content.GrammarOption("alt", "alternative", "Also fits.")),
        )
        val choices = io.github.stopcran.kanji.core.grammar.GrammarChoices.build(
            exercise, io.github.stopcran.kanji.core.GrammarChoiceRandom(),
        )
        val answer = androidx.compose.runtime.mutableStateOf(GrammarUi.Answer(exercise, "choose", "b", false, 1, choices))
        rule.setContent {
            androidx.compose.foundation.layout.Column { GrammarOptionFeedback(answer.value) }
        }
        fun assertBothCorrect() {
            for (text in listOf("✓ Correct: a — Rationale.", "✓ Correct: alternative — Also fits.")) {
                val node = rule.onNodeWithText(text).assertIsDisplayed()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(androidx.compose.ui.graphics.Color(0xFF2E7D32), layouts.single().layoutInput.style.color)
            }
        }
        assertBothCorrect()
        rule.onNodeWithText("✗ Your answer: b — Rationale.").assertIsDisplayed()
        rule.runOnIdle { answer.value = answer.value.copy(response = "") }
        assertBothCorrect()
        rule.onNodeWithText("✗ Your answer: b — Rationale.").assertDoesNotExist()
        rule.onNodeWithText("b — Rationale.").assertIsDisplayed()
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
    fun wordToPictureReadingLinksAndLargeButtonShowOnlyTheWholeWordReading() {
        val word = WordEntity("owner/repo", "卵音", "たまご", "Secret meaning", null, "", "", "Secret article", 5)
        val help = WordKanjiHelp.build(word.word, word.reading, WordStacks.n5, emptyMap())!!
        val question = WordQuizUi.Question(word, WordDirection.WordToPicture, emptyList(), 2, KanjiFont.Gothic)
        val tip = androidx.compose.runtime.mutableStateOf<WordReadingTip?>(null)
        var opens = 0
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                WordQuizPrompt(word, WordDirection.WordToPicture, KanjiFont.Gothic, true, help) {
                    opens++
                    tip.value = WordReadingTip(question, help)
                }
            }
            tip.value?.let { WordReadingTipDialog(it) { tip.value = null } }
        }
        rule.onNodeWithText(word.reading).assertDoesNotExist()
        rule.onNodeWithText("Show word reading").performClick()
        rule.onNodeWithText("Reading of 卵音").assertIsDisplayed()
        rule.onNodeWithText(word.reading).assertIsDisplayed()
        rule.onNodeWithText("This is the reading of the whole word, not of an individual kanji.").assertIsDisplayed()
        rule.onNodeWithText("Unclassified kanji: 卵, 音.").assertIsDisplayed()
        rule.onNodeWithText(word.title).assertDoesNotExist()
        rule.onNodeWithText(word.body).assertDoesNotExist()
        rule.onNodeWithText("Read the article").assertDoesNotExist()
        rule.onNodeWithText("Close").performClick()
        val prompt = rule.onNodeWithText(word.word)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        prompt.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        prompt.performTouchInput { click(layouts.single().getBoundingBox(0).center) }
        rule.onNodeWithText("Reading of 卵音").assertIsDisplayed()
        assertEquals(2, opens)
    }

    @Test
    fun japaneseOptionHighlightsKeepExactLabelsAndHaveNoNestedHelpActions() {
        val labels = listOf("難音 (なんおん)", "卵音 (たまご)")
        val helps = labels.map { label ->
            WordKanjiHelp.build(label.substringBefore(" "), label.substringAfter("(").removeSuffix(")"),
                WordStacks.n5, mapOf("難" to 3, "音" to 5))!!
        }
        var picks = 0
        val word = WordEntity("owner/repo", "難音", "なんおん", "Sound", null, "", "", "", 5)
        val options = labels.mapIndexed { index, label -> WordOption(helps[index].word, label) }
        val question = WordQuizUi.Question(word, WordDirection.EnToJp, options, 2, KanjiFont.Gothic,
            kanjiHelp = helps.associateBy { it.word })
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                WordQuizChoices(question) { picks++ }
            }
        }
        labels.forEachIndexed { index, label ->
            val node = rule.onNodeWithText(label)
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val annotated = layouts.single().layoutInput.text
            assertEquals(label, annotated.text)
            assertTrue(annotated.getLinkAnnotations(0, label.length).isEmpty())
            val style = annotated.spanStyles.single().item
            assertEquals(androidx.compose.ui.text.style.TextDecoration.Underline, style.textDecoration)
            assertEquals(
                if (index == 0) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                style.fontWeight,
            )
            node.performClick()
        }
        rule.onNodeWithContentDescription("${labels[0]}. Kanji beyond this deck's level: 難.").assertExists()
        rule.onNodeWithContentDescription("${labels[1]}. Unclassified kanji: 卵.").assertExists()
        rule.onNodeWithText("Show word reading").assertDoesNotExist()
        assertEquals(2, picks)
    }

    @Test
    fun englishChoicesNeverApplyTheJapanesePromptsHighlightOffsets() {
        val word = WordEntity("owner/repo", "卵難音", "たまご", "egg", null, "", "", "", 5)
        val help = WordKanjiHelp.build(word.word, word.reading, WordStacks.n5, emptyMap())!!
        val question = WordQuizUi.Question(word, WordDirection.JpToEn, listOf(WordOption(word.word, "egg")), 1, KanjiFont.Gothic,
            kanjiHelp = mapOf(word.word to help))
        rule.setContent { WordQuizChoices(question) {} }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("egg").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.text.spanStyles.isEmpty())
        rule.onNodeWithText("Show word reading").assertDoesNotExist()
    }

    @Test
    fun listeningEntryOffersScoredExtraOnlyAfterScheduledWorkAndHonorsBusyActiveAndEmptyStates() {
        val base = ListeningOverview(
            "owner/repo", emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), null,
            ListeningAllowance(0, 0), NewAllowance(0, 0, PacingNote.RestDay, 0, 40), null, null,
            extra = listOf("outside-plan"),
        )
        val data = androidx.compose.runtime.mutableStateOf(base)
        val busy = androidx.compose.runtime.mutableStateOf(false)
        var selected: Pair<String, Boolean>? = null
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                ListeningStartButton(data.value, busy.value) { id, extra -> selected = id to extra }
            }
        }
        rule.onNodeWithText("Practice more").performClick()
        assertEquals("outside-plan" to true, selected)
        rule.runOnIdle { data.value = base.copy(due = listOf("due"), fresh = listOf("new")) }
        rule.onNodeWithText("Practice more").assertDoesNotExist()
        rule.onNodeWithText("Start listening").performClick()
        assertEquals("due" to false, selected)
        rule.runOnIdle { data.value = base.copy(fresh = listOf("new")) }
        rule.onNodeWithText("Start listening").performClick()
        assertEquals("new" to false, selected)
        rule.runOnIdle { data.value = base; busy.value = true }
        rule.onNodeWithText("Practice more").assertIsNotEnabled()
        rule.runOnIdle {
            busy.value = false
            data.value = base.copy(active = ListeningEncounterEntity(
                "active", "owner/repo", "outside-plan", "", "", "", null, "", "graded", 0, 0, false,
            ))
        }
        rule.onNodeWithText("Practice more").assertIsNotEnabled()
        rule.runOnIdle { data.value = base.copy(extra = emptyList(), cooldowns = mapOf("cooling" to Instant.MAX)) }
        rule.onNodeWithText("Practice more").assertDoesNotExist()
        rule.onNodeWithText("Start listening").assertIsNotEnabled()
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
