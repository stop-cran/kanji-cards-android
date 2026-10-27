package io.github.stopcran.kanji.data

import android.app.Application
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.words.WordStacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class StartingGuideSettingsTest {
    @Test fun guideChoicePersistsAcrossReloadsAndSourcesWithoutChangingStudySelections() {
        val context = RuntimeEnvironment.getApplication()
        val settings = Settings(context)
        val original = settings.source.value
        settings.setWordStack(WordStacks.n4.id)
        settings.setGrammarNewScope(original.id, GrammarScope.All)
        assertTrue(settings.startingGuideVisible.value)
        settings.setStartingGuideVisible(false)

        val reopened = Settings(context)
        assertFalse(reopened.startingGuideVisible.value)
        assertTrue(reopened.setSource("https://github.com/example/fork", "main"))
        assertFalse(Settings(context).startingGuideVisible.value)
        assertEquals(WordStacks.n4.id, reopened.wordStack.value)
        assertEquals(GrammarScope.All, reopened.grammarFor(original.id).newScope)

        reopened.setStartingGuideVisible(true)
        assertTrue(reopened.setSource("https://github.com/${original.id}", original.branch))
        val revisited = Settings(context)
        assertTrue(revisited.startingGuideVisible.value)
        assertEquals(WordStacks.n4.id, revisited.wordStack.value)
        assertEquals(GrammarScope.All, revisited.grammarFor(original.id).newScope)
    }
}
