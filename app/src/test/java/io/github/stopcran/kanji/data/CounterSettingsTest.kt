package io.github.stopcran.kanji.data

import android.app.Application
import io.github.stopcran.kanji.core.counters.CounterScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CounterSettingsTest {
    @Test
    fun selectionPersistsAcrossProcessAndSourceChangesWithoutChangingOtherStudySettings() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val settings = Settings(context)
        val source = settings.source.value
        assertEquals(CounterScope.N5, settings.counterScopeFor(source.id))
        settings.setCounterScope(source.id, CounterScope.N4)
        settings.setSource("https://github.com/example/fork", "main")
        val fork = settings.source.value
        assertEquals(CounterScope.N5, settings.counterScopeFor(fork.id))
        settings.setCounterScope(fork.id, CounterScope.N3)
        val restored = Settings(context)
        assertEquals(CounterScope.N4, restored.counterScopeFor(source.id))
        assertEquals(CounterScope.N3, restored.counterScopeFor(fork.id))
        restored.setSource("https://github.com/${source.owner}/${source.repo}", source.branch)
        assertEquals(CounterScope.N4, restored.counterScopeFor(restored.source.value.id))
        assertEquals(settings.counterScopes.value, restored.counterScopes.value)
        assertEquals(settings.dailyNewCards.value, restored.dailyNewCards.value)
        assertEquals(GrammarPreferences(), restored.grammarFor(source.id))
        assertEquals(0, restored.counterParserEpoch(source.id))
    }

    @Test
    fun existingSavedCodesAndN3RoundTripWithoutChangingTheAllowance() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings", 0)
        for ((code, scope) in mapOf("n5" to CounterScope.N5, "n4" to CounterScope.N4,
            "n3" to CounterScope.N3, "all" to CounterScope.All)) {
            prefs.edit().clear().putString("counterScope:example/fork", code).commit()
            val settings = Settings(context)
            settings.setDailyNewCards(7)
            assertEquals(scope, settings.counterScopeFor("example/fork"))
            settings.setCounterScope("example/fork", CounterScope.N3)
            assertEquals("n3", prefs.getString("counterScope:example/fork", null))
            assertEquals(7, Settings(context).dailyNewCards.value)
        }
    }

    @Test
    fun invalidSavedScopeFallsBackToBeginnerWithoutBroadeningTheSelection() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("settings", 0)
        prefs.edit().clear().putString("counterScope:example/fork", "future").commit()
        assertEquals(CounterScope.N5, Settings(context).counterScopeFor("example/fork"))
    }
}
