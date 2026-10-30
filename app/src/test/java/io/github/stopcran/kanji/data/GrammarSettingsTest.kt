package io.github.stopcran.kanji.data

import android.app.Application
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class GrammarSettingsTest {
    @Test
    fun legacySyncHintsAndParserOnlySourcesRemainReadableWithoutRewritingPersistence() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings", 0)
        val source = RepoSource("example", "fork", "feature/grammar")
        val sha = "a".repeat(40)
        prefs.edit().clear()
            .putInt("grammarParserEpoch:${source.id}", GRAMMAR_LEVEL_EPOCH)
            .putString("sha:${source.id}@${source.branch}", sha)
            .putString("etag:${source.id}@${source.branch}", "malformed")
            .putLong("fallback:${source.id}@${source.branch}", 123L)
            .putLong("lastCheckMs", 456L)
            .commit()
        val before = prefs.all.toMap()
        val settings = Settings(context)
        assertEquals(before, settings.snapshot())
        assertEquals(GRAMMAR_LEVEL_EPOCH, settings.grammarPreferences.value[source.id]?.parserEpoch)
        assertEquals(sha, settings.sync.syncedCommit(source))
        assertNull(settings.sync.syncedCommit(source.copy(branch = "main")))
        assertNull(settings.sync.headEtag(source))
        assertEquals(123L, settings.sync.fallbackAttemptMs(source))
        assertEquals(456L, settings.sync.lastCheckMs)
        settings.sync.setSyncedCommit(source.copy(branch = "main"), "b".repeat(40))
        assertEquals(sha, settings.sync.syncedCommit(source))
        assertEquals("b".repeat(40), Settings(context).sync.syncedCommit(source.copy(branch = "main")))
    }

    @Test
    fun syncEpochRefreshUpdatesTheReadOnlyGrammarProjectionWithoutChangingChoices() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        val source = settings.source.value.id
        settings.setGrammarNewScope(source, GrammarScope.N3)
        settings.sync.setGrammarParserEpoch(source, GRAMMAR_LEVEL_EPOCH)
        val expected = GrammarPreferences(GrammarScope.N3, GrammarScope.All, GRAMMAR_LEVEL_EPOCH)
        assertEquals(expected, settings.grammarPreferences.value[source])
        assertThrows(IllegalArgumentException::class.java) { settings.sync.setGrammarParserEpoch(source, -1) }
        assertEquals(expected, settings.grammarPreferences.value[source])
        assertEquals(expected, Settings(context).grammarFor(source))
    }

    @Test
    fun browserRepresentationPersistsPerSourceAndBranchWithoutChangingStudyPreferences() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        val source = settings.source.value
        val branch = source.copy(branch = "feature/grammar")
        settings.setGrammarBrowser(source, GrammarBrowserPreferences(GrammarTargetKind.Set, true))
        settings.setGrammarBrowser(branch, GrammarBrowserPreferences(GrammarTargetKind.Group, false))
        val reopened = Settings(context)
        assertEquals(GrammarBrowserPreferences(GrammarTargetKind.Set, true), reopened.grammarBrowserFor(source))
        assertEquals(GrammarBrowserPreferences(GrammarTargetKind.Group, false), reopened.grammarBrowserFor(branch))
        reopened.setSource("https://github.com/example/fork", "main")
        assertEquals(GrammarBrowserPreferences(), reopened.grammarBrowserFor(reopened.source.value))
        assertEquals(GrammarPreferences(), reopened.grammarFor(source.id))
    }

    @Test
    fun scopesAndSuccessfulEpochPersistIndependentlyAcrossSourceSwitches() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        val original = settings.source.value
        settings.setGrammarNewScope(original.id, GrammarScope.N3)
        settings.setGrammarBrowseScope(original.id, GrammarScope.N5)
        settings.sync.setGrammarParserEpoch(original.id, GRAMMAR_LEVEL_EPOCH)
        settings.setSource("https://github.com/example/fork", "main")
        val fork = settings.source.value
        assertEquals(GrammarPreferences(), settings.grammarFor(fork.id))
        settings.setGrammarNewScope(fork.id, GrammarScope.Unclassified)
        settings.setGrammarBrowseScope(fork.id, GrammarScope.N1)

        val reopened = Settings(context)
        assertEquals(fork, reopened.source.value)
        val expectedOriginal = GrammarPreferences(GrammarScope.N3, GrammarScope.N5, GRAMMAR_LEVEL_EPOCH)
        val expectedFork = GrammarPreferences(GrammarScope.Unclassified, GrammarScope.N1, 0)
        assertEquals(expectedOriginal, reopened.grammarFor(original.id))
        assertEquals(expectedFork, reopened.grammarFor(fork.id))
        reopened.setSource("https://github.com/${original.owner}/${original.repo}", original.branch)
        assertEquals(expectedOriginal, reopened.grammarFor(reopened.source.value.id))
        reopened.setGrammarBrowseScope(original.id, GrammarScope.N4)
        assertEquals(expectedOriginal.copy(browseScope = GrammarScope.N4), reopened.grammarPreferences.value[original.id])
        assertEquals(expectedFork, reopened.grammarPreferences.value[fork.id])
        assertEquals(reopened.grammarPreferences.value, Settings(context).grammarPreferences.value)
    }
}
