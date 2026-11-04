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
import androidx.test.core.app.ApplicationProvider
import io.github.stopcran.kanji.core.srs.KanjiFont
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.joinSep
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WordPromptLocalizationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun canonicalWordHintsFollowTheLocaleWithoutChangingTheirIdentifiers() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val english = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val russian = english.createConfigurationContext(Configuration(english.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        })
        val context = mutableStateOf<Context>(english)
        val word = WordEntity(
            "fixture/content", "川", "かわ", "река", "wago", "川",
            listOf("noun", "nature", "wago").joinSep(), "",
        )
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme { WordQuizPrompt(word, WordDirection.Type, KanjiFont.Gothic, true) }
            }
        }
        for (localeContext in listOf(english, russian, english)) {
            rule.runOnIdle { context.value = localeContext }
            val russianLabels = localeContext.resources.configuration.locales.get(0).language == "ru"
            rule.onNodeWithText(if (russianLabels) "существительное · природа" else "noun · nature").assertIsDisplayed()
            rule.onNodeWithText(if (russianLabels) "noun · nature" else "существительное · природа").assertDoesNotExist()
            rule.onNodeWithText("wago").assertDoesNotExist()
        }
    }

    @Test fun pilotBHintsFollowTheLocaleWithoutRewritingCanonicalContentTags() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val english = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val russian = english.createConfigurationContext(Configuration(english.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        })
        val context = mutableStateOf<Context>(english)
        val word = mutableStateOf(WordEntity("fixture/content", "ぎらぎら", "ぎらぎら", "резко блестеть", "wago", "", "", ""))
        val cases = listOf(
            Triple(
                listOf("onomatopoeia", "gitaigo", "reduplication", "voiced", "appearance", "wago"),
                "onomatopoeia · gitaigo · reduplication · voiced · appearance",
                "ономатопея · образное слово · повтор · звонкие согласные · внешний вид",
            ),
            Triple(listOf("conversation", "personality"), "conversation · personality", "общение · характер"),
            Triple(
                listOf("wago", "mimetic", "expression", "onomatopoeia", "gitaigo", "sokuon", "ri-ending"),
                "mimetic · expression · onomatopoeia · gitaigo · sokuon · ri-ending",
                "образное описание · выражение · ономатопея · образное слово · маленькое っ · окончание -り",
            ),
            Triple(
                listOf("onomatopoeia", "gitaigo", "giongo", "reduplication", "texture", "movement", "wago"),
                "onomatopoeia · gitaigo · giongo · reduplication · texture · movement",
                "ономатопея · образное слово · подражание звукам · повтор · фактура · движение",
            ),
            Triple(
                listOf("wago", "onomatopoeia", "gitaigo", "giseigo", "giongo", "reduplication"),
                "onomatopoeia · gitaigo · giseigo · giongo · reduplication",
                "ономатопея · образное слово · подражание голосам · подражание звукам · повтор",
            ),
            Triple(listOf("wago", "verb", "intransitive", "light"), "verb · intransitive · light", "глагол · непереходный · свет"),
            Triple(listOf("wago", "feelings", "appearance"), "feelings · appearance", "чувства · внешний вид"),
        )
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                MaterialTheme { WordQuizPrompt(word.value, WordDirection.Type, KanjiFont.Gothic, true) }
            }
        }
        for ((tags, englishHint, russianHint) in cases) {
            rule.runOnIdle { word.value = word.value.copy(tags = tags.joinSep()) }
            for (localeContext in listOf(english, russian, english)) {
                rule.runOnIdle { context.value = localeContext }
                val russianLabels = localeContext.resources.configuration.locales.get(0).language == "ru"
                rule.onNodeWithText(if (russianLabels) russianHint else englishHint).assertIsDisplayed()
                rule.onNodeWithText(if (russianLabels) englishHint else russianHint).assertDoesNotExist()
                rule.onNodeWithText("wago").assertDoesNotExist()
            }
        }
    }
}
