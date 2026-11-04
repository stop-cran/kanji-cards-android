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
import io.github.stopcran.kanji.data.AppLanguage
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.LanguageSources
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.StudyEnd
import io.github.stopcran.kanji.data.StudySessionStore
import io.github.stopcran.kanji.data.StudyStart
import io.github.stopcran.kanji.data.SyncMetaEntity
import io.github.stopcran.kanji.isLanguageIndependentRoute
import io.github.stopcran.kanji.stateKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    fun sourceLanguageBypassRequiresACapturedExerciseAndEndsWithItsRoute() {
        val sessions = StudySessionRegistry()
        val active = "quiz:existing"
        sessions.register(active) {}
        assertFalse(sessions.hasCaptured(active))
        assertFalse(isLanguageIndependentRoute(active, sessions.hasCaptured(active)))
        sessions.markCaptured(active, "owner/repo")
        assertTrue(sessions.hasCaptured(active))
        assertEquals("owner/repo", sessions.capturedSourceId(active))
        assertTrue(isLanguageIndependentRoute(active, sessions.hasCaptured(active)))
        assertFalse(sessions.hasCaptured("quiz:practice-more"))
        assertNull(sessions.capturedSourceId("quiz:practice-more"))
        assertFalse(isLanguageIndependentRoute("quiz:practice-more", sessions.hasCaptured("quiz:practice-more")))
        sessions.leave(active)
        assertFalse(sessions.hasCaptured(active))
        assertFalse(isLanguageIndependentRoute(active, sessions.hasCaptured(active)))
    }

    @Test
    fun typedArgumentsRoundTripAndMalformedMigratedRoutesAreUnavailable() {
        val destinations = listOf(
            AppDestination.Draw("session", true),
            AppDestination.Document("kanji/語.md"),
            AppDestination.Document("articles/topic.md", "owner/repo", "Topic: / 語"),
            AppDestination.Document("articles/topic.md", "owner/repo", sessionBound = true),
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
        val sessionDocument = NavigationEntry(AppDestination.Document("articles/reference.md", "owner/repo", sessionBound = true))
        assertEquals(listOf(home), NavigationEntry.restore(listOf(home.save(), sessionDocument.save()), false))
        assertEquals(sessionDocument, NavigationEntry.restore(listOf(sessionDocument.save()), true).single())
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
    fun freshDrawingViewModelsEnforceLanguageAndRussianDefaultAdmissionFromCache() = runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            context.getSharedPreferences("settings", 0).edit().clear().commit()
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
            val settings = Settings(context)
            val registry = StudySessionRegistry()
            val root = ViewModelStore()
            try {
                val englishCustom = io.github.stopcran.kanji.core.content.RepoSource.parse(
                    "https://github.com/example/english-lessons", "main",
                )!!
                assertTrue(settings.setSource("https://github.com/${englishCustom.owner}/${englishCustom.repo}", englishCustom.branch))
                ContentSync(db, settings.sync).selectAppLanguage(settings, AppLanguage.Russian)
                db.content().putMeta(SyncMetaEntity(englishCustom.id, "en-cache", 1, 0, baseLanguage = "en"))

                val mismatchRoute = "draw:english-custom"
                val mismatchRegistry = registry.also { it.register(mismatchRoute) {} }
                val mismatchInk = CountingInk()
                val mismatch = DrawViewModel(Services(db, settings, context), mismatchInk)
                mismatch.attachSessionRegistration(mismatchRoute, mismatchRegistry)
                root.put(mismatchRoute, mismatch)
                mismatch.ensureStarted(extra = false)
                awaitDrawing(mismatch)
                assertEquals(DrawUi.Empty(DrawEmptyReason.ContentLanguageMismatch), mismatch.ui)
                assertFalse(mismatchRegistry.hasCaptured(mismatchRoute))
                assertEquals(0, mismatchInk.prepared)

                val russianDefault = LanguageSources.defaultForBase("ru")
                assertTrue(settings.setSource("https://github.com/${russianDefault.owner}/${russianDefault.repo}", russianDefault.branch))
                db.content().putMeta(SyncMetaEntity(russianDefault.id, "ru-cache", 2, 0, baseLanguage = "ru"))
                val gatedRoute = "draw:russian-default"
                registry.register(gatedRoute) {}
                val gatedInk = CountingInk()
                val gated = DrawViewModel(Services(db, settings, context), gatedInk)
                gated.attachSessionRegistration(gatedRoute, registry)
                root.put(gatedRoute, gated)
                gated.ensureStarted(extra = false)
                awaitDrawing(gated)
                assertEquals(DrawUi.Empty(DrawEmptyReason.RussianDefaultGated), gated.ui)
                assertFalse(registry.hasCaptured(gatedRoute))
                assertEquals(0, gatedInk.prepared)

                val pilotSource = LanguageSources.russianPilot
                assertTrue(settings.setSource("https://github.com/${pilotSource.owner}/${pilotSource.repo}", pilotSource.branch))
                db.content().putMeta(SyncMetaEntity(pilotSource.id, "pilot-cache", 3, 0, baseLanguage = "ru"))
                val pilotRoute = "draw:russian-pilot"
                registry.register(pilotRoute) {}
                val pilotVm = DrawViewModel(Services(db, settings, context), CountingInk())
                pilotVm.attachSessionRegistration(pilotRoute, registry)
                root.put(pilotRoute, pilotVm)
                pilotVm.ensureStarted(extra = false)
                awaitDrawing(pilotVm)
                assertEquals(DrawUi.Empty(DrawEmptyReason.NoStrokeCards), pilotVm.ui)
                assertFalse(registry.hasCaptured(pilotRoute))
            } finally {
                root.clear()
                db.close()
                Dispatchers.resetMain()
            }
    }

    private fun awaitDrawing(vm: DrawViewModel) {
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.ui is DrawUi.Loading && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
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
        var prepared = 0
        override suspend fun prepare() = ModelState.Unavailable.also { prepared++ }
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
