package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import io.github.stopcran.kanji.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LanguageResourceTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun localized(language: String): Context {
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(app.resources.configuration).apply {
            setLocale(locale)
            setLocales(LocaleList(locale))
        }
        return app.createConfigurationContext(configuration)
    }

    @Test
    fun russianPluralFormsCoverOneTwoFiveElevenAndTwentyOne() {
        val resources = localized("ru").resources
        val counts = listOf(1, 2, 5, 11, 21)
        val prompts = counts.map { resources.getQuantityString(R.plurals.counter_prompts, it, it) }
        val grammar = counts.map { resources.getQuantityString(R.plurals.grammar_contrast_exercises, it, it) }

        assertEquals(listOf(
            "1 задание в этой подборке", "2 задания в этой подборке", "5 заданий в этой подборке",
            "11 заданий в этой подборке", "21 задание в этой подборке",
        ), prompts)
        assertEquals(listOf(
            "1 упражнение на различия", "2 упражнения на различия", "5 упражнений на различия",
            "11 упражнений на различия", "21 упражнение на различия",
        ), grammar)
    }

    @Test
    fun englishPluralAndRussianLocaleResourcesStayDistinct() {
        val english = localized("en").resources
        val russian = localized("ru").resources
        assertEquals("1 prompt in this selection", english.getQuantityString(R.plurals.counter_prompts, 1, 1))
        assertEquals("2 prompts in this selection", english.getQuantityString(R.plurals.counter_prompts, 2, 2))
        assertTrue(russian.getString(R.string.home_words).contains("Слова"))
    }
}
