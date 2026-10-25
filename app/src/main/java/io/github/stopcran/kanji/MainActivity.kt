package io.github.stopcran.kanji

import android.os.Bundle
import android.net.Uri
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.toMutableStateList
import io.github.stopcran.kanji.ui.AboutScreen
import io.github.stopcran.kanji.ui.CounterQuizScreen
import io.github.stopcran.kanji.ui.GrammarQuizScreen
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
import io.github.stopcran.kanji.ui.KanaStudyScreen
import io.github.stopcran.kanji.ui.GrammarPracticeRoutes
import io.github.stopcran.kanji.ui.GrammarPracticeUnavailable
import androidx.compose.runtime.remember
import android.util.Log

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

/** Ephemeral quizzes restart after process death; listening routes resume their durable captured encounter. */
private val processToken = System.nanoTime().toString()
private val sessionPrefixes = listOf("quiz:", "wquiz:", "cquiz:", "grammarquiz:", "draw:", "kreading:", "kana-study:")

internal fun restoreStudyRoutes(routes: List<String>, sameProcess: Boolean): List<String> =
    routes.mapNotNull { saved ->
        val route = GrammarPracticeRoutes.adaptDiscovery(saved)
        when {
            sameProcess -> route
            GrammarPracticeRoutes.isTargeted(route) -> GrammarPracticeRoutes.restart(route)
            sessionPrefixes.any { route.startsWith(it) } -> null
            else -> route
        }
    }.fold(emptyList()) { result, route ->
        val previous = result.lastOrNull()
        val duplicate = (route == "grammar-home" || GrammarPracticeRoutes.isTargeted(route)) &&
            previous != null && GrammarPracticeRoutes.savedStateIdentity(previous) == GrammarPracticeRoutes.savedStateIdentity(route)
        if (duplicate) result.dropLast(1) + route else result + route
    }

/**
 * Back stack of route strings: "home", "kanji-home", "words-home", "settings", "cards", "word-cards", "quiz:..", "doc:<path>". Survives rotation via
 * rememberSaveable; after process death ephemeral session routes are dropped. Durable listening encounters are restored.
 */
@Composable
private fun AppNav(app: KanjiApp) {
    val stack = rememberSaveable(
        saver = listSaver(
            save = { listOf("t:$processToken") + it },
            restore = { saved ->
                val routes = saved.filterNot { it.startsWith("t:") }
                val sameProcess = saved.firstOrNull() == "t:$processToken"
                restoreStudyRoutes(routes, sameProcess).ifEmpty { listOf("home") }.toMutableStateList()
            },
        ),
    ) {
        listOf("home").toMutableStateList()
    }
    val holder = rememberSaveableStateHolder()
    fun push(route: String) {
        if (!GrammarPracticeRoutes.isTargeted(route) ||
            GrammarPracticeRoutes.savedStateIdentity(stack.last()) != GrammarPracticeRoutes.savedStateIdentity(route)) stack.add(route)
    }
    fun pop() {
        if (stack.size > 1) {
            app.services.studySessions.leave(stack.last())
            holder.removeState(stateKey(stack.lastIndex, stack.last()))
            stack.removeAt(stack.lastIndex)
        }
    }
    fun replace(route: String) {
        val old = stack.last()
        app.services.studySessions.leave(old)
        if (stateKey(stack.lastIndex, old) != stateKey(stack.lastIndex, route)) holder.removeState(stateKey(stack.lastIndex, old))
        stack[stack.lastIndex] = route
    }
    fun browseGrammar() {
        val index = stack.indexOfLast { it == "grammar-home" }
        if (index < 0) replace("grammar-home") else while (stack.lastIndex > index) pop()
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Screens below the top leave composition; the holder keeps their rememberSaveable state (search text, selection, scroll) for Back.
    val top = stack.last()
    holder.SaveableStateProvider(stateKey(stack.lastIndex, top)) {
        RouteContent(app, top, { push(it) }, { pop() }, { replace(it) }, { browseGrammar() })
    }
}

/** Index in the key so the same document opened twice in one stack keeps separate scroll state. */
internal fun stateKey(index: Int, route: String) = "$index:${GrammarPracticeRoutes.savedStateIdentity(route)}"

private fun sourceDocRoute(sourceId: String, path: String) = "source-doc:${Uri.encode(sourceId)}:${Uri.encode(path)}"

@Composable
private fun RouteContent(
    app: KanjiApp,
    route: String,
    push: (String) -> Unit,
    pop: () -> Unit,
    replace: (String) -> Unit,
    browseGrammar: () -> Unit,
) {
    when (route) {
        "settings" -> SettingsScreen(app, onBack = pop, onAbout = { push("about") })
        "about" -> AboutScreen(onBack = pop)
        "cards" -> CardsScreen(app, onBack = pop, onOpen = { push("doc:kanji/$it.md") }, onOpenDoc = { push("doc:$it") })
        "word-cards" -> WordCardsScreen(app, onBack = pop, onOpen = { push("doc:words/$it.md") }, onOpenDoc = { push("doc:$it") })
        "listening-home" -> io.github.stopcran.kanji.ui.ListeningPage(app, pop, push)
        GrammarPracticeRoutes.UNAVAILABLE -> GrammarPracticeUnavailable(pop, browseGrammar)
        else -> if (route.startsWith("listening-assessment-preview:")) {
            io.github.stopcran.kanji.ui.ListeningAssessmentPreviewScreen(route, pop, replace)
        } else if (route.startsWith("listening-assessment:")) {
            io.github.stopcran.kanji.ui.ListeningAssessmentScreen(route, pop) { source, path -> push(sourceDocRoute(source, path)) }
        } else if (route.startsWith("listening-task:")) {
            io.github.stopcran.kanji.ui.ListeningTaskScreen(route, pop) { source, path -> push(sourceDocRoute(source, path)) }
        } else if (route.startsWith("listening:")) {
            io.github.stopcran.kanji.ui.ListeningScreen(route, pop) { source, path -> push(sourceDocRoute(source, path)) }
        } else if (route.startsWith("quiz:")) {
            QuizScreen(
                sessionKey = route,
                onBack = pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("quiz:${System.currentTimeMillis()}:extra") },
            )
        } else if (route.startsWith("wquiz:")) {
            WordQuizScreen(
                sessionKey = route,
                onBack = pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { d -> pop(); push("wquiz:${System.currentTimeMillis()}:${d?.code ?: route.split(":")[2]}:extra") },
            )
        } else if (route.startsWith("cquiz:")) {
            CounterQuizScreen(
                sessionKey = route,
                onBack = pop,
                onPracticeMore = {
                    val group = io.github.stopcran.kanji.core.counters.CounterGroup.fromRoute(route)
                    pop(); push("cquiz:${System.currentTimeMillis()}:${route.split(":")[2]}:${group.code}:extra")
                },
                onOpenDoc = { source, path -> push(sourceDocRoute(source, path)) },
            )
        } else if (route.startsWith("grammarquiz:")) {
            val target = remember(route) {
                if (!GrammarPracticeRoutes.isTargeted(route)) null else try {
                    GrammarPracticeRoutes.target(route)
                } catch (error: IllegalArgumentException) {
                    Log.w("GrammarPractice", "Invalid targeted-practice route", error)
                    null
                }
            }
            if (target == null && !GrammarPracticeRoutes.isOrdinary(route)) {
                GrammarPracticeUnavailable(pop, browseGrammar)
            } else {
                GrammarQuizScreen(
                    sessionKey = route,
                    onBack = pop,
                    onOpenDoc = { sourceId, path -> push(sourceDocRoute(sourceId, path)) },
                    onPracticeMore = {
                        if (target == null) {
                            pop()
                            push("grammarquiz:${System.currentTimeMillis()}:extra")
                        } else {
                            replace(GrammarPracticeRoutes.create(target))
                        }
                    },
                    onOpenTopic = { push(GrammarPracticeRoutes.create(it)) },
                    onBrowse = browseGrammar,
                    target = target,
                    interrupted = route.endsWith(":restart"),
                )
            }
        } else if (route.startsWith("kreading:")) {
            KanjiReadingScreen(
                sessionKey = route,
                onBack = pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("kreading:${System.currentTimeMillis()}:extra") },
            )
        } else if (route.startsWith("draw:")) {
            DrawScreen(
                sessionKey = route,
                onBack = pop,
                onOpenDoc = { push("doc:$it") },
                onPracticeMore = { pop(); push("draw:${System.currentTimeMillis()}:extra") },
            )
        } else if (route.startsWith("kana-study:")) {
            KanaStudyScreen(
                sessionKey = route,
                onBack = pop,
                onPracticeMore = { script, mode ->
                    pop()
                    push("kana-study:${script.id}:${mode.name}:${System.currentTimeMillis()}:extra")
                },
            )
        } else if (route.startsWith("source-doc:")) {
            val (sourceId, path) = route.removePrefix("source-doc:").split(":", limit = 2).map(Uri::decode)
            DocScreen(app, path, onBack = pop, onOpenDoc = { push(sourceDocRoute(sourceId, it)) }, sourceId = sourceId)
        } else if (route.startsWith("doc:")) {
            val path = route.removePrefix("doc:")
            DocScreen(app, path, onBack = pop, onOpenDoc = { push("doc:$it") })
        } else {
            HomePages(
                app,
                route,
                onBack = pop,
                actions = HomeActions(
                    onSettings = { push("settings") },
                    onCards = { push("cards") },
                    onWordCards = { push("word-cards") },
                    onKanjiPage = { push("kanji-home") },
                    onWordsPage = { push("words-home") },
                    onCountersPage = { push("counters-home") },
                    onGrammarPage = { push("grammar-home") },
                    onListeningPage = { push("listening-home") },
                    onOpenDoc = { push("doc:$it") },
                    onGrammarDoc = { sourceId, path -> push(sourceDocRoute(sourceId, path)) },
                    onKanaPage = { push("kana-home") },
                    onKanaStudy = { script, mode, extra ->
                        push("kana-study:${script.id}:${mode.name}:${System.currentTimeMillis()}" + if (extra) ":extra" else "")
                    },
                    onCounters = { mode, extra, group ->
                        push("cquiz:${System.currentTimeMillis()}:${mode.code}:${group.code}" + if (extra) ":extra" else "")
                    },
                    onGrammar = { extra -> push("grammarquiz:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onGrammarTarget = { push(GrammarPracticeRoutes.create(it)) },
                    onQuiz = { extra -> push("quiz:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onDraw = { extra -> push("draw:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onReadings = { extra -> push("kreading:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onWords = { dir, extra -> push("wquiz:${System.currentTimeMillis()}:${dir.code}" + if (extra) ":extra" else "") },
                ),
            )
        }
    }
}
