package io.github.stopcran.kanji.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.room.withTransaction
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarFacet
import io.github.stopcran.kanji.core.content.GrammarIntroductionGroup
import io.github.stopcran.kanji.core.content.GrammarMember
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncMetaEntity
import io.github.stopcran.kanji.data.grammarStudy
import io.github.stopcran.kanji.stateKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File
import java.time.Instant

/** Production details/question host with an isolated database and preferences, never learner history. */
class GrammarBrowserFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun focusedGuidanceIsSharedAndOptionalWhileOrdinaryAndExtraPracticeStayUnguided() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "grammar-guidance-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferencesName, mode)
        }
        val settings = Settings(isolated).also {
            it.setGrammarParserEpoch(it.source.value.id, ContentParser.GRAMMAR_PARSER_EPOCH)
            it.setGrammarNewScope(it.source.value.id, GrammarScope.N5)
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(6)
        }
        val source = settings.source.value.id
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val services = Services(db, settings, context)
        val models = ViewModelStore()
        val active = mutableStateOf<Pair<String, GrammarQuizViewModel>?>(null)
        val home = mutableStateOf<GrammarHome?>(null)
        val restoration = StateRestorationTester(rule)
        fun open(extra: Boolean) {
            val key = "grammar:${UUID.randomUUID()}" + if (extra) ":extra" else ""
            active.value = key to GrammarQuizViewModel(services).also { models.put(key, it) }
        }
        fun awaitQuestion() {
            rule.waitUntil(10_000) { active.value?.second?.ui.let { it is GrammarUi.Choose || it is GrammarUi.Type } }
            rule.onNodeWithText("Attempts left:", substring = true).performScrollTo().assertIsDisplayed()
        }
        fun returnHome() {
            repeat(2) {
                if (active.value != null) rule.onNodeWithContentDescription("Back").performClick()
            }
            rule.waitUntil(10_000) { active.value == null && home.value != null }
        }
        fun capture(name: String) {
            File(context.getExternalFilesDir(null), name).outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            runBlocking {
                val set = fixture("focus-topic").let { it.copy(exercises = it.exercises.map { e -> e.copy(jlpt = 5) }) }
                db.content().insertGrammar(listOf(GrammarEntity(source, set.id, Json.encodeToString(GrammarSet.serializer(), set), 0)))
                db.content().putMeta(SyncMetaEntity(source, "fixture", System.currentTimeMillis(), 0))
            }
            restoration.setContent {
                val preferences by settings.grammarPreferences.collectAsState()
                val budget by settings.dailyNewCards.collectAsState()
                LaunchedEffect(preferences, budget, active.value) {
                    if (active.value == null) {
                        val study = db.grammarStudy(settings, source, Instant.now())
                        home.value = GrammarHome(source, study.preferences, study.sets, study.mode, study.focus, study.notice)
                    }
                }
                KanjiTheme {
                    val current = active.value
                    if (current == null) Page("Grammar fixture") {
                        home.value?.let {
                            ModeRow("Choose or type the construction", it.mode, onStart = ::open)
                            GrammarFocusControls(services, it)
                        } ?: Text("Loading fixture")
                    } else GrammarQuizScreen(current.first, {
                        current.second.leaveSession()
                        home.value = null
                        active.value = null
                    }, { _, _, _ -> }, { open(true) }, {}, {}, vm = current.second)
                }
            }
            rule.waitUntil(10_000) { home.value != null }
            rule.onNode(isToggleable()).assertIsOff()
            rule.onNodeWithText("Show focus details").assertDoesNotExist()
            rule.onNodeWithText("Start").performScrollTo().performClick()
            awaitQuestion()
            assertNull(active.value!!.second.focus)
            rule.onNodeWithText("Show focus details").assertDoesNotExist()
            returnHome()

            rule.onNode(isToggleable()).performScrollTo().performClick()
            rule.waitUntil(10_000) { home.value?.focus?.current != null }
            rule.onNode(isToggleable()).assertIsOn()
            val focus = requireNotNull(home.value!!.focus)
            val label = requireNotNull(focus.current).group.label
            rule.onNodeWithText("Next focus: $label").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Familiarity is not mastery.", substring = true).assertIsDisplayed()
            rule.onNodeWithText(focus.reason).assertDoesNotExist()
            capture("ux103-preview.png")
            rule.onNodeWithText("Show focus details").performScrollTo().performClick()
            rule.onNodeWithText(focus.reason).performScrollTo().assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithText("Hide focus details").performScrollTo().assertIsDisplayed().performClick()
            assertNull(runBlocking { db.reviews().grammarFocus(source) })
            rule.onNodeWithText("Start").performScrollTo().performClick()
            awaitQuestion()
            assertEquals(focus.current!!.group.id, active.value!!.second.focus!!.current!!.group.id)
            rule.onNodeWithText("Focus: $label").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Familiarity is not mastery.", substring = true).assertIsDisplayed()
            rule.onNodeWithText(focus.reason).assertDoesNotExist()
            capture("ux103-session.png")
            rule.onNodeWithText("Show focus details").performScrollTo().performClick()
            rule.onNodeWithText("Details from the start of this session.").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(focus.reason).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Recommendation priority:", substring = true).performScrollTo().assertIsDisplayed()
            capture("ux103-details.png")
            rule.onNodeWithText("Hide focus details").performScrollTo().performClick()
            returnHome()

            rule.runOnIdle { settings.setDailyNewCards(0) }
            rule.waitUntil(10_000) { home.value?.mode == io.github.stopcran.kanji.core.home.ModeState.PracticeMore }
            val cursor = runBlocking { db.reviews().grammarFocus(source) }
            rule.onNodeWithText("Practice more").performScrollTo().performClick()
            awaitQuestion()
            assertNull(active.value!!.second.focus)
            rule.onNodeWithText("Show focus details").assertDoesNotExist()
            assertEquals(cursor, runBlocking { db.reviews().grammarFocus(source) })
            returnHome()
            rule.onNode(isToggleable()).performScrollTo().performClick()
            rule.waitUntil(10_000) { home.value?.preferences?.guided == false }
            rule.onNode(isToggleable()).assertIsOff()
            rule.onNodeWithText("Show focus details").assertDoesNotExist()
            assertEquals(0, runBlocking { db.reviews().allLog().size })
        } finally {
            rule.runOnIdle { models.clear() }
            runBlocking {
                withTimeout(10_000) {
                    while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
                }
            }
            db.close()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    @Test
    fun acceptedPracticeShowsQuestionsAndAbandonmentAndCompletionReturnFreshDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "grammar-flow-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferencesName, mode)
        }
        val settings = Settings(isolated)
        val source = settings.source.value
        settings.setGrammarParserEpoch(source.id, ContentParser.GRAMMAR_PARSER_EPOCH)
        settings.setGrammarNewScope(source.id, GrammarScope.All)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val services = Services(db, settings, context)
        val target = GrammarPracticeTarget(source.id, source.branch, GrammarTargetKind.Group, "topic")
        val route = mutableStateOf(GrammarPracticeRoutes.create(target))
        val vm = mutableStateOf(GrammarQuizViewModel(services, target = target))
        fun fresh() {
            vm.value.leaveSession()
            route.value = GrammarPracticeRoutes.create(target)
            vm.value = GrammarQuizViewModel(services, target = target)
        }
        try {
            runBlocking {
                db.content().insertGrammar(listOf(fixture("foundation"), fixture("topic", listOf("foundation"))).mapIndexed { index, set ->
                    GrammarEntity(source.id, set.id, Json.encodeToString(GrammarSet.serializer(), set), index)
                })
                db.content().putMeta(SyncMetaEntity(source.id, "fixture", System.currentTimeMillis(), 0))
            }
            rule.setContent {
                KanjiTheme {
                    val holder = rememberSaveableStateHolder()
                    holder.SaveableStateProvider(stateKey(1, route.value)) {
                        GrammarQuizScreen(
                            route.value, { error("Active practice must return to fresh details, not pop its parent") },
                            { _, _, _ -> }, { fresh() }, {}, {}, target = target, vm = vm.value,
                        )
                    }
                }
            }
            awaitPreview(vm.value)
            val first = route.value
            startRestricted()
            rule.waitUntil(10_000) { vm.value.ui is GrammarUi.Choose }
            rule.onNodeWithText("Attempts left: 4").assertIsDisplayed()
            rule.onNodeWithContentDescription("Back").performClick()
            awaitPreview(vm.value)
            assertNotEquals(first, route.value)
            rule.onNode(isToggleable()).performScrollTo().assertIsOff()
            assertEquals(1, runBlocking { db.reviews().allSessions().size })
            startRestricted()
            rule.waitUntil(10_000) { vm.value.ui is GrammarUi.Choose }
            repeat(4) {
                when (val state = vm.value.ui) {
                    is GrammarUi.Choose -> rule.onNodeWithText(state.exercise.answer).performScrollTo().performClick()
                    is GrammarUi.Type -> {
                        rule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(state.exercise.target)
                        rule.onNodeWithText("Check").performScrollTo().performClick()
                    }
                    else -> error("Expected a question: $state")
                }
                rule.onNodeWithText("Next").assertIsDisplayed().performClick()
                rule.waitForIdle()
            }
            rule.onNodeWithText("Session complete").performScrollTo().assertIsDisplayed()
            val completed = route.value
            rule.onNodeWithText("Practice more").performScrollTo().performClick()
            awaitPreview(vm.value)
            assertNotEquals(completed, route.value)
            assertFalse(vm.value.practiceOverride)
            rule.onNodeWithText("Practice").performScrollTo().assertIsDisplayed()
            runBlocking {
                db.withTransaction {}
                assertEquals(listOf("left", "completed"), db.reviews().allSessions().sortedBy { it.startedAtMs }.map { it.endReason })
                assertEquals(4, db.reviews().allLog().size)
                assertEquals(4, db.reviews().allGrammarResponses().size)
            }
        } finally {
            rule.runOnIdle { vm.value.leaveSession() }
            runBlocking { db.withTransaction {} }
            db.close()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    private fun awaitPreview(vm: GrammarQuizViewModel) {
        rule.waitUntil(10_000) { vm.ui is GrammarUi.Preview }
        rule.waitForIdle()
    }

    private fun startRestricted() {
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Practice").performScrollTo().performClick()
    }

    private fun fixture(id: String, requires: List<String> = emptyList()): GrammarSet {
        val targets = listOf("a", "b")
        val exercises = targets.flatMap { target -> (0..1).map { index ->
            GrammarExercise("$id-$target-$index", target, "contrast", "Synthetic ____.", "Synthetic meaning.", "Evidence.", "Explanation.",
                target, listOf(target), targets.map { GrammarOption(it, it, "Rationale.") }, 4)
        } }
        val group = GrammarIntroductionGroup(id, id, targets.map { GrammarFacet(it, it) },
            exercises.map { GrammarMember(it.id, it.target, it.id.substringAfterLast('-')) }, requires = requires)
        return GrammarSet(1, id, id, "articles/x.md", targets.map { GrammarConstruction(it, it) },
            listOf(GrammarRelationship("contrast", "contrast", targets)), exercises, listOf(group))
    }
}
