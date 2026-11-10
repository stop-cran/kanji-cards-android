package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Rect
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.CounterCard
import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.counters.CounterGroup
import io.github.stopcran.kanji.core.counters.CounterMode
import io.github.stopcran.kanji.core.counters.CounterRoutes
import io.github.stopcran.kanji.core.counters.CounterScope
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.COUNTER_STACK
import io.github.stopcran.kanji.data.CounterEntity
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncMetaEntity
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TypingFocusFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val animations = AtomicInteger()
    private val visible = mutableStateOf(true)

    @Before fun prepareWindow() {
        rule.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            ViewCompat.setWindowInsetsAnimationCallback(it.window.decorView,
                object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) animations.incrementAndGet()
                    }
                    override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>) = insets
                    override fun onEnd(animation: WindowInsetsAnimationCompat) {
                        if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) animations.decrementAndGet()
                    }
                })
        }
    }

    @Test fun hiraganaToRomaji() = kana(KanaScript.Hiragana, KanaMode.TypeKanaToRomaji)
    @Test fun romajiToHiragana() = kana(KanaScript.Hiragana, KanaMode.TypeRomajiToKana)
    @Test fun katakanaToRomaji() = kana(KanaScript.Katakana, KanaMode.TypeKanaToRomaji)
    @Test fun romajiToKatakana() = kana(KanaScript.Katakana, KanaMode.TypeRomajiToKana)
    @Test fun counterTyping() = counter(CounterMode.Type)
    @Test fun counterWriting() = counter(CounterMode.Expression)
    @Test fun counterReading() = counter(CounterMode.Reading)

    @Test fun wordTyping() = fixture { fixture ->
        val vm = WordQuizViewModel(fixture.services)
        val route = "wquiz:${UUID.randomUUID()}:ty"
        fixture.models.put(route, vm)
        show { WordQuizScreen(route, {}, {}, {}, vm, onContinue = {}) }
        repeat(3) { step ->
            rule.waitUntil(10_000) { vm.ui is WordQuizUi.TypeQuestion }
            assertAutomaticKeyboard("I don't know")
            if (step == 0) assertDismissalSurvivesDraftChange { vm.onDraft("test") }
            giveUpAndAdvance("I don't know")
        }
    }

    @Test fun grammarTypingBetweenChoiceQuestions() = fixture { fixture ->
        val vm = GrammarQuizViewModel(fixture.services)
        val route = "grammarquiz:${UUID.randomUUID()}"
        fixture.models.put(route, vm)
        show { GrammarQuizScreen(route, {}, { _, _, _ -> }, {}, {}, {}, vm = vm) }
        repeat(3) { step ->
            rule.waitUntil(10_000) { vm.ui is GrammarUi.Choose }
            rule.onNode(hasSetTextAction()).assertDoesNotExist()
            awaitKeyboard(false)
            rule.onNodeWithText("I don't know").performScrollTo().performClick()
            rule.onNodeWithText("Next").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { vm.ui is GrammarUi.Type }
            assertAutomaticKeyboard("I don't know")
            if (step == 0) assertDismissalSurvivesDraftChange { vm.onDraft("test") }
            giveUpAndAdvance("I don't know", fixedNext = true)
        }
    }

    private fun kana(script: KanaScript, mode: KanaMode) = fixture { fixture ->
        val vm = KanaStudyViewModel(fixture.services)
        val route = "kana:${script.id}:${mode.name}:${UUID.randomUUID()}"
        fixture.models.put(route, vm)
        show { KanaStudyScreen(route, {}, { _, _ -> }, vm) }
        val seen = mutableListOf<String>()
        repeat(3) { step ->
            rule.waitUntil(10_000) { vm.ui is KanaStudyUi.TypeQuestion }
            seen += rule.runOnIdle { (vm.ui as KanaStudyUi.TypeQuestion).card.kana }
            assertAutomaticKeyboard("I don't remember")
            if (step == 0) assertDismissalSurvivesDraftChange { vm.onDraft("test") }
            giveUpAndAdvance("I don't remember")
        }
        assertEquals(seen[0], seen[2])
    }

    private fun counter(mode: CounterMode) = fixture { fixture ->
        val vm = CounterQuizViewModel(fixture.services.counters, fixture.services.sessionStore)
        val route = CounterRoutes.create(mode, CounterGroup.All, CounterScope.N5)
        fixture.models.put(route, vm)
        show { CounterQuizScreen(route, {}, {}, { _, _ -> }, vm, onContinue = {}) }
        repeat(3) { step ->
            rule.waitUntil(10_000) { vm.ui is CounterUi.TypeQuestion || vm.ui is CounterUi.ExpressionQuestion }
            assertAutomaticKeyboard("I don't know")
            if (step == 0) assertDismissalSurvivesDraftChange { vm.onDraft("test") }
            giveUpAndAdvance("I don't know")
        }
    }

    private fun show(content: @Composable () -> Unit) {
        rule.setContent { MaterialTheme { if (visible.value) content() } }
    }

    private fun assertAutomaticKeyboard(uncertainty: String) {
        rule.onNode(hasSetTextAction()).assertIsFocused()
        awaitKeyboard(true)
        val check = rule.onNodeWithText("Check").performScrollTo().assertIsDisplayed()
        val unsure = rule.onNodeWithText(uncertainty).performScrollTo().assertIsDisplayed()
        val keyboardTop = rule.runOnIdle {
            val decor = rule.activity.window.decorView
            val frame = Rect()
            decor.getWindowVisibleDisplayFrame(frame)
            val screen = IntArray(2)
            val window = IntArray(2)
            decor.getLocationOnScreen(screen)
            decor.getLocationInWindow(window)
            frame.bottom - (screen[1] - window[1])
        }
        val checkBottom = check.fetchSemanticsNode().boundsInWindow.bottom
        val unsureBottom = unsure.fetchSemanticsNode().boundsInWindow.bottom
        assertTrue("Check bottom $checkBottom is below keyboard top $keyboardTop", checkBottom <= keyboardTop)
        assertTrue("Uncertainty bottom $unsureBottom is below keyboard top $keyboardTop", unsureBottom <= keyboardTop)
    }

    private fun assertDismissalSurvivesDraftChange(change: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
        awaitKeyboard(false)
        rule.runOnIdle(change)
        rule.waitForIdle()
        assertTrue("Draft recomposition reopened the keyboard", !keyboardVisible())
    }

    private fun giveUpAndAdvance(uncertainty: String, fixedNext: Boolean = false) {
        rule.onNodeWithText(uncertainty).performScrollTo().performClick()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        awaitKeyboard(false)
        val next = rule.onNodeWithText("Next")
        if (!fixedNext) next.performScrollTo()
        next.assertIsDisplayed().performClick()
    }

    private fun keyboardVisible(): Boolean {
        val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
        return insets?.isVisible(WindowInsetsCompat.Type.ime()) == true &&
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
    }

    private fun awaitKeyboard(shown: Boolean) {
        rule.waitUntil(10_000) { keyboardVisible() == shown && animations.get() == 0 }
    }

    private fun fixture(exercise: (Fixture) -> Unit) {
        Fixture().use { fixture ->
            try {
                exercise(fixture)
            } finally {
                rule.runOnIdle { visible.value = false; fixture.models.clear() }
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferenceName = "typing-focus-${UUID.randomUUID()}"
        private val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferenceName, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        private val settings = Settings(isolated).apply {
            setSource("https://github.com/fixture/typing-focus", "main")
            setDailyNewCards(2)
            setWeekPlan("FFFFFFF")
            setWordStack(WordStacks.n5.id)
            sync.setGrammarParserEpoch(source.value.id, ContentParser.GRAMMAR_PARSER_EPOCH)
            setGrammarNewScope(source.value.id, GrammarScope.All)
        }
        val services = Services(db, settings, context)
        val models = ViewModelStore()

        init {
            val source = settings.source.value.id
            runBlocking {
                val words = listOf("猫" to "ねこ", "犬" to "いぬ")
                db.content().insertWords(words.map { (word, reading) ->
                    WordEntity(source, word, reading, "Fixture cue for $word", "wago", word, "", "Fixture body", 5)
                })
                words.forEach { (word) -> known(source, WordStacks.n5.id, word, StudyMode.WordEnJp) }
                val cards = (1..2).map {
                    CounterCard("pens-$it", text = "ペンが三{}あります。", en = "Three pens.", noun = "ペン",
                        phrase = "三本", reading = "さんぼん", good = listOf("本"), kana = listOf("さんぼん"),
                        distractors = listOf("匹", "冊", "回"))
                }
                val expressions = (1..2).map {
                    CounterExpression("days-$it", "Duration: one day.", "一日", listOf("一日"),
                        "いちにち", listOf("いちにち"), "Fixture explanation.")
                }
                val files = listOf(CounterFile(1, "pens", "本", "ほん", "Pens", cards = cards),
                    CounterFile(2, "days", "日", "にち", "Days", expressions = expressions))
                db.content().insertCounters(files.mapIndexed { index, file ->
                    CounterEntity(source, file.id, Json.encodeToString(CounterFile.serializer(), file), index)
                })
                cards.forEach { known(source, COUNTER_STACK, it.id, StudyMode.CounterChoose) }
                val targets = listOf("a", "b")
                val grammar = GrammarSet(1, "focus", "Focus fixture", "articles/focus.md",
                    targets.map { GrammarConstruction(it, it) }, listOf(GrammarRelationship("contrast", "Contrast", targets)),
                    (1..2).map { index ->
                        GrammarExercise("focus-$index", "a", "contrast", "Synthetic ____.", "Complete the fixture.",
                            "Evidence.", "Explanation.", "a", listOf("a"),
                            targets.map { GrammarOption(it, it, "Rationale.") }, 4)
                    })
                db.content().insertGrammar(listOf(GrammarEntity(source, grammar.id,
                    Json.encodeToString(GrammarSet.serializer(), grammar), 0)))
                db.content().putMeta(SyncMetaEntity(source, "typing-focus-fixture", System.currentTimeMillis(), 0))
            }
        }

        private suspend fun known(source: String, stack: String, item: String, mode: StudyMode) {
            val now = System.currentTimeMillis()
            db.reviews().putState(ReviewStateEntity(source, stack, item, mode.name, CardPhase.Review.name,
                30.0, 5.0, now + 86_400_000, now - 86_400_000, 3, 0))
        }

        override fun close() {
            try {
                runBlocking {
                    withTimeout(10_000) {
                        while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
                    }
                    db.withTransaction {}
                }
            } finally {
                db.close()
                context.deleteSharedPreferences(preferenceName)
            }
        }
    }
}
