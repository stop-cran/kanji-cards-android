package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.core.quiz.QuizSession
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.StudyEnd
import io.github.stopcran.kanji.data.StudySessionStore
import io.github.stopcran.kanji.data.StudyStart
import io.github.stopcran.kanji.stateKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class NavigationBoundaryTest {
    @Test
    fun typedArgumentsRoundTripAndMalformedMigratedRoutesAreUnavailable() {
        val destinations = listOf(
            AppDestination.Draw("session", true),
            AppDestination.Document("kanji/語.md"),
            AppDestination.Document("articles/topic.md", "owner/repo", "Topic: / 語"),
        )
        destinations.forEach { assertEquals(it, AppDestination.parse(it.route)) }
        for (route in listOf("draw:", "draw:id:unknown", "draw:id:extra:extra", "source-doc:missing", "doc:../outside.md")) {
            assertEquals(route, AppDestination.Unavailable, AppDestination.parse(route))
        }
    }

    @Test
    fun restorationPreservesEntryIdsButDropsEphemeralRoutesAndRestartsGrammarConsent() {
        val home = NavigationEntry(AppDestination.Legacy("home"))
        val draw = NavigationEntry(AppDestination.Draw())
        val document = NavigationEntry(AppDestination.Document("articles/reference.md"))
        val duplicate = NavigationEntry(document.destination)
        val listening = NavigationEntry(AppDestination.Legacy("listening:durable-encounter"))
        val original = listOf(home, draw, document, duplicate, listening)
        assertEquals(original, NavigationEntry.restore(original.map { it.save() }, true))
        val restored = NavigationEntry.restore(original.map { it.save() }, false)
        assertEquals(listOf(home, document, duplicate, listening), restored)
        assertEquals(stateKey(2, document), stateKey(1, restored[1]))
        assertNotEquals(stateKey(2, document), stateKey(3, duplicate))
        val target = GrammarPracticeTarget("owner/repo", "feature/grammar", GrammarTargetKind.Group, "group")
        val grammar = NavigationEntry(AppDestination.Legacy(GrammarPracticeRoutes.create(target)))
        val afterDeath = NavigationEntry.restore(listOf(home.save(), grammar.save(), document.save()), false)
        assertNotEquals(grammar.destination.route, afterDeath[1].destination.route)
        assertEquals(target, GrammarPracticeRoutes.target(afterDeath[1].destination.route))
        assertEquals(stateKey(1, grammar), stateKey(1, afterDeath[1]))
        assertEquals(GrammarPracticeRoutes.UNAVAILABLE, NavigationEntry.restore(
            listOf("home", "grammarquiz:id:target:bad"), false,
        ).last().destination.route)
        assertEquals(listOf("home", "doc:articles/reference.md"), NavigationEntry.restore(
            listOf("home", "draw:old", "doc:articles/reference.md"), false,
        ).map { it.destination.route })
    }

    @Test
    fun drawResourceSurvivesCoverageAndHostRecreationButClosesOnceOnRemoval() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = ViewModelStore()
        val owners = RouteOwners()
        root.put("routes", owners)
        val ink = CountingInk()
        try {
            val draw = DrawViewModel(Services(db, Settings(context), context), ink)
            val owner = owners.owner("draw")
            owner.viewModelStore.put("session", draw)
            owners.hostState(Lifecycle.State.RESUMED)
            owners.show("draw")
            assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
            owners.owner("document")
            owners.show("document")
            assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
            owners.hostState(Lifecycle.State.DESTROYED)
            assertEquals(0, ink.closed)
            owners.hostState(Lifecycle.State.RESUMED)
            owners.show("draw")
            assertSame(owner, owners.owner("draw"))
            assertSame(draw, owner.viewModelStore.get("session"))
            owners.remove("draw")
            assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
            assertEquals(1, ink.closed)
            owners.remove("draw")
            assertEquals(1, ink.closed)
        } finally {
            root.clear()
            db.close()
        }
    }

    @Test
    fun clearingTheRouteStoreKeepsPendingReviewAheadOfTheLeftMarker() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val events = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val store = object : StudySessionStore {
            override suspend fun start(value: StudyStart) { events += "start" }
            override suspend fun end(value: StudyEnd) {
                assertEquals(1, value.answered)
                assertEquals(1, value.correct)
                events += value.reason
            }
        }
        val owners = RouteOwners()
        val root = ViewModelStore().also { it.put("routes", owners) }
        val vm = PendingStudy(store) { gate.await(); events += "review" }
        try {
            owners.owner("study").viewModelStore.put("session", vm)
            vm.start()
            runCurrent()
            vm.answer()
            runCurrent()
            owners.remove("study")
            runCurrent()
            assertEquals(listOf("start"), events)
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("start", "review", "left"), events)
        } finally {
            gate.complete(Unit)
            root.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class CountingInk : InkRecognition {
        var closed = 0
        override suspend fun prepare() = ModelState.Unavailable
        override suspend fun candidates(strokes: List<List<TimedPt>>): List<String>? = null
        override fun close() { closed++ }
    }

    private class PendingStudy(store: StudySessionStore, private val write: suspend () -> Unit) : StudyViewModel(store) {
        private var session = QuizSession(listOf("item"), { it })
        fun start() {
            viewModelScope.launch { session = trackSession(session, "source/repo", "stack", "Draw", false) }
        }
        fun answer() {
            session.answer("item", true)
            recordSession(write)
        }
    }
}
