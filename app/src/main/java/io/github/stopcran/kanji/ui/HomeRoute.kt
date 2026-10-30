package io.github.stopcran.kanji.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.srs.NewAllowance
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.Settings
import kotlinx.coroutines.flow.first
import java.time.Clock

internal class HomePreferencesActions(
    val selectStack: (String) -> Unit,
    val selectWordStack: (String) -> Unit,
    val acceptN4: () -> Unit,
    val dismissN4: () -> Unit,
    val showStartingGuide: (Boolean) -> Unit,
)

internal fun homePreferences(settings: Settings, clock: Clock = Clock.systemDefaultZone()) = HomePreferencesActions(
    settings::setStack, settings::setWordStack,
    { settings.setN4Unlocked(true); settings.setWordStack(WordStacks.n4.id) },
    { settings.dismissAdvance(clock.millis()) }, settings::setStartingGuideVisible,
)

internal class HomeOverview(
    val kana: String, val counters: String, val grammar: String, val listening: String,
    val kanaAllowance: NewAllowance?, val startingGuideVisible: Boolean,
)

/** Data/platform boundary; the scoped Home, Kanji and Words presentation only sees state and actions. */
@Composable
internal fun HomeRoute(app: KanjiApp, route: String, onBack: () -> Unit, actions: HomeActions) {
    val source by app.settings.source.collectAsState()
    key(source) {
        val state = rememberHomeState(app.services, app.syncCoordinator.activity)
        val preferences = remember(app.settings) { homePreferences(app.settings) }
        val retry: () -> Unit = { app.syncCoordinator.request(source) }
        LaunchedEffect(source) {
            if (!app.services.home.ready(source).first()) app.syncCoordinator.request(source)
        }
        val section = when (route) {
            "kanji-home" -> "Kanji"
            "words-home" -> "Words"
            "counters-home" -> "Numbers & counters"
            "grammar-home" -> "Grammar"
            else -> null
        }
        if (section != null) {
            ContentSection(state.content, section, onBack, retry) {
                when (route) {
                    "kanji-home" -> KanjiPage(state.data, preferences, onBack, actions)
                    "words-home" -> WordsPage(preferences, state.data, onBack, actions.onWordCards, actions.onWords)
                    "counters-home" -> CountersPage(app, onBack, actions)
                    else -> GrammarPage(app, onBack, actions.onGrammarDoc, actions.onGrammar, actions.onGrammarTarget)
                }
            }
        } else if (route == "kana-home") {
            KanaPage(app, onBack, actions.onKanaStudy)
        } else {
            val kana = rememberKanaOverview(app)
            val guideVisible by app.settings.startingGuideVisible.collectAsState()
            MainPage(
                state,
                HomeOverview(
                    kana.summary(), rememberCounterHome(app).summary(), rememberGrammarHome(app).value.summary(),
                    rememberListeningHome(app).value.summary(), kana.allowance, guideVisible,
                ),
                preferences, actions, retry,
            )
        }
    }
}
