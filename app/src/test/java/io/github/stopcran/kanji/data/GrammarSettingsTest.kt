package io.github.stopcran.kanji.data

import android.app.Application
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class GrammarSettingsTest {
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
        settings.setGrammarParserEpoch(original.id, GRAMMAR_LEVEL_EPOCH)
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
