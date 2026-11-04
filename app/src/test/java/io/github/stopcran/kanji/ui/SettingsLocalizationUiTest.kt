package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.requiredWidth
import androidx.test.core.app.ApplicationProvider
import io.github.stopcran.kanji.core.srs.Commitment
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.R
import io.github.stopcran.kanji.data.AppLanguage
import io.github.stopcran.kanji.data.LanguageSources
import io.github.stopcran.kanji.data.SyncCoordinator
import io.github.stopcran.kanji.data.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SettingsLocalizationUiTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun russianSettingsChoicesAndRepositoryActionsRemainReachableAtNarrowScaledWidth() {
        val baseContext = ApplicationProvider.getApplicationContext<Application>()
        val russianConfiguration = Configuration(baseContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        }
        val russianContext = baseContext.createConfigurationContext(russianConfiguration)
        val selected = mutableStateOf(Commitment.Light)
        var saved = 0
        var defaulted = 0
        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides russianContext,
                LocalConfiguration provides russianContext.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.5f),
            ) {
                MaterialTheme {
                    Column(Modifier.requiredWidth(280.dp)) {
                        CommitmentChoices(selected.value) { selected.value = it }
                        RepositorySourceActions(
                            saveEnabled = true,
                            onSaveAndSync = { saved++ },
                            onUseDefault = { defaulted++ },
                        )
                    }
                }
            }
        }
        listOf("Небольшая нагрузка (5)", "Обычная нагрузка (10)", "Интенсивно (20)").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        rule.onNodeWithText("Интенсивно (20)").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(Commitment.Intensive, selected.value) }
        rule.onNodeWithText("Сохранить и синхронизировать").assertIsDisplayed().performClick()
        rule.onNodeWithText("Использовать репозиторий по умолчанию").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, saved)
            assertEquals(1, defaulted)
        }
    }

    @Test
    fun customSourceConfirmationSurvivesCancellationAtLocaleRecreation() {
        val app = KanjiApp()
        ReflectionHelpers.callInstanceMethod<Unit>(
            app, "attachBaseContext",
            ClassParameter.from(Context::class.java, ApplicationProvider.getApplicationContext<Application>()),
        )
        app.settings.setSource("https://github.com/example/russian-lessons", "pilot")
        val customSource = app.settings.source.value
        app.settings.sync.bindBaseLanguage(app.settings.source.value.id, "ru")
        runBlocking { app.contentSync.selectAppLanguage(app.settings, AppLanguage.English) }
        val controller = Robolectric.buildActivity(RecreationCancelsActivity::class.java).setup()
        val context = controller.get().apply { testApplication = app }
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sync = SyncCoordinator(syncScope, app.contentSync.activity) { SyncResult.UpToDate }
        val restoration = StateRestorationTester(rule)
        try {
            restoration.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme { SettingsScreen(app, {}, {}, sync) }
                }
            }
            rule.onNodeWithText(app.getString(R.string.switch_to_matching_default))
                .performScrollTo().performClick()
            rule.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithText(app.getString(R.string.language_use_default_source)).assertIsDisplayed()
            rule.runOnIdle { assertEquals(customSource, app.settings.source.value) }
            rule.onNodeWithText(app.getString(R.string.language_keep_source)).performClick()
            rule.onNodeWithText(app.getString(R.string.language_use_default_source)).assertDoesNotExist()
            rule.runOnIdle { assertEquals(customSource, app.settings.source.value) }

            rule.onNodeWithText(app.getString(R.string.switch_to_matching_default))
                .performScrollTo().performClick()
            rule.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithText(app.getString(R.string.language_use_default_source))
                .assertIsDisplayed().performClick()
            rule.waitUntil {
                app.settings.source.value == LanguageSources.defaultForBase("en")
            }
            rule.onNodeWithText("https://github.com/stop-cran/learning-japanese").assertExists()
            rule.onNodeWithText("main").assertExists()
            rule.runOnIdle { assertEquals(AppLanguage.English, app.settings.appLanguage.value) }
        } finally {
            syncScope.cancel()
            controller.pause().stop().destroy()
            app.db.close()
        }
    }

    class RecreationCancelsActivity : ComponentActivity() {
        var testApplication: KanjiApp? = null

        override fun getApplicationContext(): Context = testApplication ?: super.getApplicationContext()

        override fun recreate() {
            throw CancellationException("Activity recreation cancels the Settings callback")
        }
    }
}
