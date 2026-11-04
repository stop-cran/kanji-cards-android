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
}
