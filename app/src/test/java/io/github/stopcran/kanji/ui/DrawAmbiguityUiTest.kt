package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.stopcran.kanji.core.DrawingAmbiguityFixtures
import io.github.stopcran.kanji.core.draw.DrawOutcome
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.data.KanjiEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DrawAmbiguityUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun englishRussianEnglishShowsAllExplicitActionsWithoutDefaultSelectionOrNext() {
        val application = RuntimeEnvironment.getApplication()
        fun localized(language: String): Context =
            application.createConfigurationContext(Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
        val context = mutableStateOf(localized("en"))
        val case = DrawingAmbiguityFixtures.cases.last()
        val reference = DrawingAmbiguityFixtures.references.getValue(case.target)
        val card = KanjiEntity(
            "fixture/drawing", case.target, "Fixture meaning", 5, "", reference.size,
            null, null, "", "", "", "", DrawingAmbiguityFixtures.strokesJson(case.target), 0,
        )
        val question = DrawUi.Question(card, 7, 0)
        val drawn = case.drawn.map { stroke ->
            stroke.mapIndexed { index, point -> TimedPt(point.x.toFloat(), point.y.toFloat(), index.toLong()) }
        }
        val answer = DrawUi.Answer(
            card, 7, reference, drawn, 109f, DrawOutcome.Mistakes,
            StrokeMatcher().match(reference, case.drawn), false,
        )
        val state = DrawUi.Ambiguous(question, answer, Lookalike(case.alternative, "Fixture alternative", true))
        val choices = mutableListOf<DrawResolution>()
        var retries = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme {
                    Page("Drawing") { DrawAmbiguityContent(state, { choices += it }, { retries++ }) }
                }
            }
        }
        rule.runOnIdle {
            assertEquals(0, choices.size)
            assertEquals(0, retries)
        }
        for (language in listOf("en", "ru", "en")) {
            rule.runOnIdle { context.value = localized(language) }
            val russian = language == "ru"
            val retry = if (russian) "Повторить без оценки" else "Try again without grading"
            val target = if (russian) {
                "Считать ожидаемым кандзи с ошибками в чертах"
            } else {
                "Treat as the expected character with stroke mistakes"
            }
            val other = if (russian) "Считать другим знаком" else "Treat as a different character"
            rule.onNodeWithText(if (russian) "Осталось вопросов: 7" else "Questions left: 7")
                .performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(if (russian) "Напишите кандзи со значением" else "Draw the kanji for")
                .performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Fixture meaning").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(if (russian) "Уточните результат этой попытки" else "Please check this attempt")
                .performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Next").assertDoesNotExist()
            rule.onNodeWithText("Далее").assertDoesNotExist()
            rule.onNodeWithText("日", substring = true).assertDoesNotExist()
            val before = choices.size
            val beforeRetries = retries
            rule.waitForIdle()
            assertEquals(before, choices.size)
            assertEquals(beforeRetries, retries)
            rule.onNodeWithText(retry).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(beforeRetries + 1, retries)
            assertEquals(before, choices.size)
            rule.onNodeWithText(target).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(DrawResolution.TargetWithMistakes, choices.last())
            rule.onNodeWithText(other).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(DrawResolution.OtherCharacter, choices.last())
            assertEquals(before + 2, choices.size)
        }
    }
}
