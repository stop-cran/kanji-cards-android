package io.github.stopcran.kanji

import io.github.stopcran.kanji.core.counters.CounterRoutes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.stopcran.kanji.ui.KanjiTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.toMutableStateList
import io.github.stopcran.kanji.ui.AboutScreen
import io.github.stopcran.kanji.ui.CounterQuizScreen
import io.github.stopcran.kanji.ui.GrammarQuizScreen
import io.github.stopcran.kanji.ui.DocRoute
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.stopcran.kanji.ui.DrawScreen
import io.github.stopcran.kanji.ui.CardsScreen
import io.github.stopcran.kanji.ui.HomeActions
import io.github.stopcran.kanji.ui.HomeRoute
import io.github.stopcran.kanji.ui.KanjiReadingScreen
import io.github.stopcran.kanji.ui.QuizScreen
import io.github.stopcran.kanji.ui.SettingsScreen
import io.github.stopcran.kanji.ui.WordQuizScreen
import io.github.stopcran.kanji.ui.WordCardsScreen
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.ui.KanaStudyScreen
import io.github.stopcran.kanji.ui.GrammarPracticeRoutes
import io.github.stopcran.kanji.ui.GrammarPracticeUnavailable
import io.github.stopcran.kanji.ui.AppDestination
import io.github.stopcran.kanji.ui.NavigationEntry
import io.github.stopcran.kanji.ui.RouteOwners
import io.github.stopcran.kanji.ui.RouteLifecycle
import io.github.stopcran.kanji.ui.DrawViewModel
import io.github.stopcran.kanji.ui.serviceViewModel
import io.github.stopcran.kanji.ui.Page
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.remember
import android.util.Log

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as KanjiApp
        // Not again after rotation or process-recreation; the interval check inside also throttles it.
        if (savedInstanceState == null) app.syncCoordinator.request(app.settings.source.value, io.github.stopcran.kanji.data.SyncKind.Launch)
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
 * Back stack for [RouteContent]. Survives rotation via
 * rememberSaveable; after process death ephemeral session routes are dropped. Durable listening encounters are restored.
 */
@Composable
internal fun AppNav(app: KanjiApp, createDraw: (Services) -> DrawViewModel = { DrawViewModel(it) }) {
    val stack = rememberSaveable(
        saver = listSaver(
            save = { listOf("t:$processToken") + it.map(NavigationEntry::save) },
            restore = { saved ->
                val routes = saved.filterNot { it.startsWith("t:") }
                val sameProcess = saved.firstOrNull() == "t:$processToken"
                NavigationEntry.restore(routes, sameProcess).ifEmpty { listOf(NavigationEntry(AppDestination.Legacy("home"))) }.toMutableStateList()
            },
        ),
    ) {
        listOf(NavigationEntry(AppDestination.Legacy("home"))).toMutableStateList()
    }
    val owners: RouteOwners = viewModel()
    val holder = rememberSaveableStateHolder()
    fun push(destination: AppDestination) {
        val route = destination.route
        if (!GrammarPracticeRoutes.isTargeted(route) ||
            GrammarPracticeRoutes.savedStateIdentity(stack.last().destination.route) != GrammarPracticeRoutes.savedStateIdentity(route)) {
            stack.add(NavigationEntry(destination))
        }
    }
    fun pop() {
        if (stack.size > 1) {
            val old = stack.last()
            app.services.studySessions.leave(old.destination.route)
            owners.remove(old.id)
            holder.removeState(stateKey(stack.lastIndex, old))
            stack.removeAt(stack.lastIndex)
        }
    }
    fun replace(destination: AppDestination) {
        val old = stack.last()
        val next = NavigationEntry(destination)
        app.services.studySessions.leave(old.destination.route)
        owners.remove(old.id)
        if (stateKey(stack.lastIndex, old) != stateKey(stack.lastIndex, next)) holder.removeState(stateKey(stack.lastIndex, old))
        stack[stack.lastIndex] = next
    }
    fun browseGrammar() {
        val index = stack.indexOfLast { it.destination.route == "grammar-home" }
        if (index < 0) replace(AppDestination.Legacy("grammar-home")) else while (stack.lastIndex > index) pop()
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Screens below the top leave composition; the holder keeps their rememberSaveable state (search text, selection, scroll) for Back.
    val top = stack.last()
    val owner = if (top.scoped) owners.owner(top.id) else null
    RouteLifecycle(owners, top.id.takeIf { top.scoped })
    val content: @Composable () -> Unit = {
        RouteContent(app, top.destination, { push(it) }, { pop() }, { replace(it) }, { browseGrammar() }, createDraw)
    }
    holder.SaveableStateProvider(stateKey(stack.lastIndex, top)) {
        if (owner == null) content() else {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides owner) { content() }
        }
    }
}

/** Legacy browsing identity is distinct from a fresh Grammar session/consent identity. */
internal fun stateKey(index: Int, route: String) = "$index:${GrammarPracticeRoutes.savedStateIdentity(route)}"

internal fun stateKey(index: Int, entry: NavigationEntry) =
    if (entry.scoped) "entry:${entry.id}" else stateKey(index, entry.destination.route)

internal fun sourceDocRoute(sourceId: String, path: String, topic: String? = null) =
    AppDestination.Document(path, sourceId, topic).route

@Composable
internal fun SourceDocScreen(app: KanjiApp, route: String, pop: () -> Unit, push: (String) -> Unit) {
    val destination = remember(route) { AppDestination.parse(route) }
    if (destination is AppDestination.Document) DocumentDestination(app, destination, pop) { push(it.route) }
    else UnavailableDestination(pop)
}

@Composable
private fun DocumentDestination(app: KanjiApp, destination: AppDestination.Document, pop: () -> Unit, push: (AppDestination) -> Unit) {
    val currentSource by app.settings.source.collectAsState()
    DocRoute(
        app.services.documents, destination.sourceId ?: currentSource.id, destination.path, pop,
        { push(AppDestination.Document(it, destination.sourceId)) }, destination.topic,
    )
}

@Composable
private fun UnavailableDestination(pop: () -> Unit) {
    Page(stringResource(R.string.destination_unavailable), pop) {
        Text(stringResource(R.string.destination_unavailable_detail))
    }
}

@Composable
private fun RouteContent(
    app: KanjiApp, destination: AppDestination, push: (AppDestination) -> Unit, pop: () -> Unit,
    replace: (AppDestination) -> Unit, browseGrammar: () -> Unit, createDraw: (Services) -> DrawViewModel,
) {
    when (destination) {
        is AppDestination.Draw -> DrawScreen(
            destination, pop, { push(AppDestination.Document(it)) },
            { replace(AppDestination.Draw(extra = true)) },
            serviceViewModel(destination.route) { createDraw(it) },
        )
        is AppDestination.Document -> DocumentDestination(app, destination, pop, push)
        AppDestination.Unavailable -> UnavailableDestination(pop)
        is AppDestination.Legacy -> LegacyRouteContent(
            app, destination.route, { push(AppDestination.parse(it)) }, pop, { replace(AppDestination.parse(it)) }, browseGrammar,
        )
    }
}

@Composable
private fun LegacyRouteContent(
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
                    val next = CounterRoutes.extra(route)
                    pop(); push(next)
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
                    onOpenDoc = { sourceId, path, topic -> push(sourceDocRoute(sourceId, path, topic)) },
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
        } else if (route.startsWith("kana-study:")) {
            KanaStudyScreen(
                sessionKey = route,
                onBack = pop,
                onPracticeMore = { script, mode ->
                    pop()
                    push("kana-study:${script.id}:${mode.name}:${System.currentTimeMillis()}:extra")
                },
            )
        } else {
            HomeRoute(
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
                    onGrammarDoc = { sourceId, path, topic -> push(sourceDocRoute(sourceId, path, topic)) },
                    onKanaPage = { push("kana-home") },
                    onKanaStudy = { script, mode, extra ->
                        push("kana-study:${script.id}:${mode.name}:${System.currentTimeMillis()}" + if (extra) ":extra" else "")
                    },
                    onCounters = { mode, extra, group, scope ->
                        push(CounterRoutes.create(mode, group, scope, extra))
                    },
                    onGrammar = { extra -> push("grammarquiz:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onGrammarTarget = { push(GrammarPracticeRoutes.create(it)) },
                    onQuiz = { extra -> push("quiz:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onDraw = { extra -> push(AppDestination.Draw(extra = extra).route) },
                    onReadings = { extra -> push("kreading:${System.currentTimeMillis()}" + if (extra) ":extra" else "") },
                    onWords = { dir, extra -> push("wquiz:${System.currentTimeMillis()}:${dir.code}" + if (extra) ":extra" else "") },
                ),
            )
        }
    }
}
