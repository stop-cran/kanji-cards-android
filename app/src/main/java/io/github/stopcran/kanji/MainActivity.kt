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
import io.github.stopcran.kanji.ui.HomeScreen
import io.github.stopcran.kanji.ui.QuizScreen
import io.github.stopcran.kanji.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KanjiApp
        lifecycleScope.launch { app.contentSync.syncOnLaunch(app.settings.source.value) }
        enableEdgeToEdge()
        setContent { KanjiTheme { AppNav(app) } }
    }
}

/** Back stack of route strings: "home", "settings", "cards", "quiz", "card:<kanji>". Survives rotation via rememberSaveable. */
@Composable
private fun AppNav(app: KanjiApp) {
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        listOf("home").toMutableStateList()
    }
    fun push(route: String) { stack.add(route) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val route = stack.last()) {
        "settings" -> SettingsScreen(app, onBack = ::pop, onAbout = { push("about") })
        "about" -> AboutScreen(onBack = ::pop)
        "cards" -> CardsScreen(app, onBack = ::pop, onOpen = { push("doc:kanji/$it.md") })
        else -> if (route.startsWith("quiz:")) {
            QuizScreen(
                sessionKey = route,
                onBack = ::pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("quiz:${System.currentTimeMillis()}:extra") },
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
            HomeScreen(
                app,
                onSettings = { push("settings") },
                onCards = { push("cards") },
                onQuiz = { extra -> push("quiz:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                onDraw = { extra -> push("draw:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
            )
        }
    }
}
