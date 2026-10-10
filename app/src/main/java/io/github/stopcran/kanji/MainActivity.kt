package io.github.stopcran.kanji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.stopcran.kanji.ui.KanjiTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import io.github.stopcran.kanji.ui.AboutScreen
import io.github.stopcran.kanji.ui.DocScreen
import io.github.stopcran.kanji.ui.DrawScreen
import io.github.stopcran.kanji.ui.CardsScreen
import io.github.stopcran.kanji.ui.HomeActions
import io.github.stopcran.kanji.ui.HomePages
import io.github.stopcran.kanji.ui.KanjiReadingScreen
import io.github.stopcran.kanji.ui.QuizScreen
import io.github.stopcran.kanji.ui.SettingsScreen
import io.github.stopcran.kanji.ui.WordQuizScreen
import io.github.stopcran.kanji.ui.WordCardsScreen
import io.github.stopcran.kanji.core.words.WordDirection

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KanjiApp
        // Not again after rotation or process-recreation; the interval check inside also throttles it.
        if (savedInstanceState == null) lifecycleScope.launch { app.contentSync.syncOnLaunch(app.settings.source.value) }
        enableEdgeToEdge()
        setContent { KanjiTheme { AppNav(app) } }
    }
}

/** Identifies this process; a saved back stack from another process (process death) must not resume a half-finished session. */
private val processToken = System.nanoTime().toString()
private val sessionPrefixes = listOf("quiz:", "wquiz:", "draw:", "kreading:")

/**
 * Back stack of route strings: "home", "kanji-home", "words-home", "settings", "cards", "word-cards", "quiz:..", "doc:<path>". Survives rotation via
 * rememberSaveable; after process death session routes are dropped, which returns to the page the session was started from.
 */
@Composable
private fun AppNav(app: KanjiApp) {
    val stack = rememberSaveable(
        saver = listSaver(
            save = { listOf("t:$processToken") + it },
            restore = { saved ->
                val routes = saved.filterNot { it.startsWith("t:") }
                val sameProcess = saved.firstOrNull() == "t:$processToken"
                (if (sameProcess) routes else routes.filterNot { r -> sessionPrefixes.any { r.startsWith(it) } }).ifEmpty { listOf("home") }.toMutableStateList()
            },
        ),
    ) {
        listOf("home").toMutableStateList()
    }
    fun push(route: String) { stack.add(route) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val route = stack.last()) {
        "settings" -> SettingsScreen(app, onBack = ::pop, onAbout = { push("about") })
        "about" -> AboutScreen(onBack = ::pop)
        "cards" -> CardsScreen(app, onBack = ::pop, onOpen = { push("doc:kanji/$it.md") }, onOpenDoc = { push("doc:$it") })
        "word-cards" -> WordCardsScreen(app, onBack = ::pop, onOpen = { push("doc:words/$it.md") }, onOpenDoc = { push("doc:$it") })
        else -> if (route.startsWith("quiz:")) {
            QuizScreen(
                sessionKey = route,
                onBack = ::pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("quiz:${System.currentTimeMillis()}:extra") },
            )
        } else if (route.startsWith("wquiz:")) {
            WordQuizScreen(
                sessionKey = route,
                onBack = ::pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("wquiz:${System.currentTimeMillis()}:${route.split(":")[2]}:extra") },
            )
        } else if (route.startsWith("kreading:")) {
            KanjiReadingScreen(
                sessionKey = route,
                onBack = ::pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("kreading:${System.currentTimeMillis()}:extra") },
            )
        } else if (route.startsWith("draw:")) {
            DrawScreen(
                sessionKey = route,
                onBack = ::pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("draw:${System.currentTimeMillis()}:extra") },
            )
        } else if (route.startsWith("doc:")) {
            val path = route.removePrefix("doc:")
            DocScreen(app, path, onBack = ::pop, onOpenDoc = { push("doc:$it") })
        } else {
            HomePages(
                app,
                route,
                onBack = ::pop,
                actions = HomeActions(
                    onSettings = { push("settings") },
                    onCards = { push("cards") },
                    onWordCards = { push("word-cards") },
                    onKanjiPage = { push("kanji-home") },
                    onWordsPage = { push("words-home") },
                    onQuiz = { extra -> push("quiz:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onDraw = { extra -> push("draw:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onReadings = { extra -> push("kreading:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onWords = { dir, extra -> push("wquiz:${System.currentTimeMillis()}:${when (dir) { WordDirection.JpToEn -> "jp"; WordDirection.EnToJp -> "en"; WordDirection.Reading -> "rd" }}" + if (extra) ":extra" else "") },
                ),
            )
        }
    }
}
