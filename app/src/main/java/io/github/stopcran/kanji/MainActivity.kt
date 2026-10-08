package io.github.stopcran.kanji

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import io.github.stopcran.kanji.ui.CardDetailScreen
import io.github.stopcran.kanji.ui.CardsScreen
import io.github.stopcran.kanji.ui.HomeScreen
import io.github.stopcran.kanji.ui.QuizScreen
import io.github.stopcran.kanji.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KanjiApp
        setContent { MaterialTheme { AppNav(app) } }
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
        "settings" -> SettingsScreen(app, onBack = ::pop)
        "cards" -> CardsScreen(app, onBack = ::pop, onOpen = { push("card:$it") })
        "quiz" -> QuizScreen(onBack = ::pop)
        else -> if (route.startsWith("card:")) {
            CardDetailScreen(app, route.removePrefix("card:"), onBack = ::pop)
        } else {
            HomeScreen(app, onSettings = { push("settings") }, onCards = { push("cards") }, onQuiz = { push("quiz") })
        }
    }
}
