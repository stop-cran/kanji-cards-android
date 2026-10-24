package io.github.stopcran.kanji.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.room.withTransaction
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
import io.github.stopcran.kanji.stateKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Production details/question host with an isolated database and preferences, never learner history. */
class GrammarBrowserFlowTest {
    @get:Rule val rule = createComposeRule()

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
                            { _, _ -> }, { fresh() }, {}, {}, target = target, vm = vm.value,
                        )
                    }
                }
            }
            awaitPreview(vm.value)
            val first = route.value
            startRestricted()
            rule.waitUntil(10_000) { vm.value.ui is GrammarUi.Choose }
            rule.onNodeWithText("Questions remaining: 4").assertIsDisplayed()
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
                rule.onNodeWithText("Next").performScrollTo().performClick()
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
