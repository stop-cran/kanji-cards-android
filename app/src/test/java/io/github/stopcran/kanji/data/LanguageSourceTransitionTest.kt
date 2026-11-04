package io.github.stopcran.kanji.data

import android.app.Application
import android.os.LocaleList
import androidx.room.Room
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.RepoSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LanguageSourceTransitionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val savedLocales = LocaleList.getDefault()
    private lateinit var db: AppDatabase
    private lateinit var settings: Settings
    private lateinit var sync: ContentSync

    @Before
    fun setup() {
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        LocaleList.setDefault(LocaleList.forLanguageTags("en"))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = Settings(context)
        sync = ContentSync(db, settings.sync)
    }

    @After
    fun cleanup() {
        db.close()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        LocaleList.setDefault(savedLocales)
    }

    @Test
    fun languageChangeMovesBuiltInSourceButPreservesCustomSource() = runBlocking {
        settings.setSource("https://github.com/stop-cran/learning-japanese", "main")

        assertEquals(LanguageSources.defaultForBase("ru"), sync.selectAppLanguage(settings, AppLanguage.Russian))
        assertEquals(LanguageSources.defaultForBase("ru"), settings.source.value)

        val custom = RepoSource("example", "lessons", "branch")
        settings.setSource("https://github.com/${custom.owner}/${custom.repo}", custom.branch)
        assertTrue(settings.sync.bindBaseLanguage(custom.id, "en"))
        assertNull(sync.selectAppLanguage(settings, AppLanguage.Russian))
        assertEquals(custom, settings.source.value)
    }

    @Test
    fun systemLocaleChangeReconcilesOnlyTheBuiltInSourceOnReturn() = runBlocking {
        settings.setSource("https://github.com/stop-cran/learning-japanese", "main")
        sync.selectAppLanguage(settings, AppLanguage.System)

        LocaleList.setDefault(LocaleList.forLanguageTags("ru-RU"))
        assertEquals(LanguageSources.defaultForBase("ru"), sync.selectAppLanguage(settings, AppLanguage.System))
        assertEquals(LanguageSources.defaultForBase("ru"), settings.source.value)

        val custom = RepoSource("example", "lessons", "branch")
        settings.setSource("https://github.com/${custom.owner}/${custom.repo}", custom.branch)
        LocaleList.setDefault(LocaleList.forLanguageTags("en-US"))
        assertNull(sync.selectAppLanguage(settings, AppLanguage.System))
        assertEquals(custom, settings.source.value)
    }

    @Test
    fun explicitEnglishThenSystemRecoversLatestIntentOnTheSameBranch() = runBlocking {
        val english = LanguageSources.defaultForBase("en")
        settings.setSource("https://github.com/${english.owner}/${english.repo}", english.branch)
        sync.selectAppLanguage(settings, AppLanguage.English)

        try {
            sync.selectAppLanguage(settings, AppLanguage.System) {
                throw IllegalStateException("simulated crash before marker acknowledgement")
            }
            fail("Expected the locale-application boundary to fail.")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated crash before marker acknowledgement", expected.message)
        }

        val restartedSettings = Settings(context)
        val restartedSync = ContentSync(db, restartedSettings.sync)
        val recoveredToken = restartedSync.recoverCommittedCandidate(restartedSettings)
        assertTrue(recoveredToken?.startsWith("source-selection-pending:system:") == true)
        assertEquals(AppLanguage.System, restartedSettings.appLanguage.value)
        assertEquals(english, restartedSettings.source.value)
        restartedSync.acknowledgeLanguageSelection(requireNotNull(recoveredToken))
        assertFalse(db.sourceAcceptance().current()?.token?.startsWith("source-selection-pending:") == true)
    }

    @Test
    fun systemThenExplicitEnglishRecoversLatestIntentOnTheSameBranch() = runBlocking {
        val english = LanguageSources.defaultForBase("en")
        settings.setSource("https://github.com/${english.owner}/${english.repo}", english.branch)
        sync.selectAppLanguage(settings, AppLanguage.System)

        try {
            sync.selectAppLanguage(settings, AppLanguage.English) {
                throw IllegalStateException("simulated crash before marker acknowledgement")
            }
            fail("Expected the locale-application boundary to fail.")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated crash before marker acknowledgement", expected.message)
        }

        val restartedSettings = Settings(context)
        val restartedSync = ContentSync(db, restartedSettings.sync)
        val recoveredToken = restartedSync.recoverCommittedCandidate(restartedSettings)
        assertTrue(recoveredToken?.startsWith("source-selection-pending:en:") == true)
        assertEquals(AppLanguage.English, restartedSettings.appLanguage.value)
        assertEquals(english, restartedSettings.source.value)
        restartedSync.acknowledgeLanguageSelection(requireNotNull(recoveredToken))
        assertFalse(db.sourceAcceptance().current()?.token?.startsWith("source-selection-pending:") == true)
    }

    @Test
    fun sameLanguageRecoveryRetainsTheAcceptedCustomSource() = runBlocking {
        val custom = RepoSource("example", "lessons", "branch")
        settings.setSource("https://github.com/${custom.owner}/${custom.repo}", custom.branch)
        assertTrue(settings.sync.bindBaseLanguage(custom.id, "en"))

        try {
            sync.selectAppLanguage(settings, AppLanguage.English) {
                throw IllegalStateException("simulated crash before marker acknowledgement")
            }
            fail("Expected the locale-application boundary to fail.")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated crash before marker acknowledgement", expected.message)
        }

        val restartedSettings = Settings(context)
        val restartedSync = ContentSync(db, restartedSettings.sync)
        val recoveredToken = restartedSync.recoverCommittedCandidate(restartedSettings)
        assertTrue(recoveredToken?.startsWith("source-selection-pending:en:") == true)
        assertEquals(AppLanguage.English, restartedSettings.appLanguage.value)
        assertEquals(custom, restartedSettings.source.value)
        restartedSync.acknowledgeLanguageSelection(requireNotNull(recoveredToken))
        assertEquals(custom.id, db.sourceAcceptance().current()?.sourceId)
        assertEquals(custom.branch, db.sourceAcceptance().current()?.branch)
    }

    @Test
    fun staleSourceCandidatesCannotReplaceTheAcceptedSourceAndRepositoryLanguageIsStable() {
        val accepted = RepoSource("example", "accepted", "main")
        settings.setSource("https://github.com/${accepted.owner}/${accepted.repo}", accepted.branch)
        val first = requireNotNull(settings.beginSourceCandidate("https://github.com/example/candidate", "draft-a"))
        val second = requireNotNull(settings.beginSourceCandidate("https://github.com/example/candidate", "draft-b"))

        assertFalse(settings.isCurrentSourceCandidate(first.token))
        assertFalse(settings.acceptSourceCandidate(acceptance(first, "ru")))
        assertEquals(accepted, settings.source.value)
        assertTrue(settings.isCurrentSourceCandidate(second.token))

        val restarted = Settings(context)
        assertEquals(second.token, restarted.pendingSourceCandidate.value?.token)
        assertTrue(restarted.acceptSourceCandidate(acceptance(second, "en")))
        assertEquals(second.source, restarted.source.value)
        assertFalse(restarted.isCurrentSourceCandidate(second.token))

        assertTrue(restarted.sync.bindBaseLanguage(second.source.id, "en"))
        assertFalse(restarted.sync.bindBaseLanguage(second.source.id, "ru"))
    }

    private fun acceptance(candidate: PendingSourceCandidate, language: String) = AcceptedSourceEntity(
        token = candidate.token,
        sourceId = candidate.source.id,
        url = "https://github.com/${candidate.source.owner}/${candidate.source.repo}",
        branch = candidate.source.branch,
        baseLanguage = language,
        head = "a".repeat(40),
        grammarParserEpoch = ContentParser.GRAMMAR_PARSER_EPOCH,
        counterParserEpoch = io.github.stopcran.kanji.core.counters.CounterExpressions.PARSER_EPOCH,
        sceneParserEpoch = io.github.stopcran.kanji.core.content.SceneCatalog.PARSER_EPOCH,
        listeningParserEpoch = io.github.stopcran.kanji.core.listening.ListeningContent.PARSER_EPOCH,
        acceptedAtMs = 1,
    )
}
