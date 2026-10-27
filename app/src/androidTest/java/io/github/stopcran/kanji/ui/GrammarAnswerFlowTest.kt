package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
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
import io.github.stopcran.kanji.core.content.Article
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.content.WordArticle
import io.github.stopcran.kanji.core.grammar.GrammarChoices
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ArticleEntity
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.SyncMetaEntity
import io.github.stopcran.kanji.data.toEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Production answer UI; all content/history and preferences are isolated from learner data. */
class GrammarAnswerFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun legacyFragmentsRemainUsable() = exercise(listOf(fixture(false)), fixtureArticles())
    @Test fun wholeSentenceBlanksRemainUsable() = exercise(listOf(fixture(true)), fixtureArticles())

    @Test fun realContentQuestionsAndFeedback() {
        val args = InstrumentationRegistry.getArguments()
        val setId = args.getString("grammarSet")
        assumeTrue("Specify grammarSet and provide grammar-acceptance.zip", setId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.getExternalFilesDir(null), "grammar-acceptance.zip")
        assertTrue("The requested real-corpus archive is missing", archive.isFile)
        val parsed = archive.inputStream().use { ContentParser.parse(SafeZip.read(it)) }
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        val set = parsed.grammar.single { it.id == setId }
        val selected = args.getString("grammarIds")?.split(',')?.toSet()
            ?: set.exercises.map { it.id }.toSet()
        assertTrue(set.exercises.map { it.id }.containsAll(selected))
        exercise(parsed.grammar, parsed.articles, parsed.words, selected)
    }

    private fun exercise(
        sets: List<GrammarSet>,
        articles: List<Article>,
        words: List<WordArticle> = emptyList(),
        selected: Set<String> = sets.flatMap { it.exercises }.map { it.id }.toSet(),
    ) {
        require(selected.size in 4..50)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferencesName = "grammar-answer-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferencesName, mode)
        }
        val settings = Settings(isolated).apply {
            setDailyNewCards(50)
            setWeekPlan("FFFFFFF")
            setGrammarParserEpoch(source.value.id, ContentParser.GRAMMAR_PARSER_EPOCH)
            setGrammarNewScope(source.value.id, GrammarScope.All)
        }
        val source = settings.source.value.id
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val vm = GrammarQuizViewModel(Services(db, settings, context))
        val document = mutableStateOf<String?>(null)
        val session = "grammarquiz:${UUID.randomUUID()}"
        try {
            runBlocking {
                db.content().insertGrammar(sets.mapIndexed { index, set ->
                    GrammarEntity(source, set.id, Json.encodeToString(GrammarSet.serializer(), set), index)
                })
                db.content().insertArticles(articles.map { ArticleEntity(source, it.slug, it.title, it.body) })
                db.content().insertWords(words.map { it.toEntity(source) })
                db.content().putMeta(SyncMetaEntity(source, "native-answer-fixture", System.currentTimeMillis(), 0))
                val known = SrsState(CardPhase.Review, 30.0, 5.0, Instant.now().plus(30, ChronoUnit.DAYS),
                    Instant.now().minus(2, ChronoUnit.DAYS), 3, 0)
                sets.flatMap { it.exercises }.filterNot { it.id in selected }.forEach {
                    db.reviews().putState(known.toEntity(source, GRAMMAR_STACK, it.id, StudyMode.Grammar))
                }
            }
            rule.setContent {
                val dark = InstrumentationRegistry.getArguments().getString("grammarTheme") == "dark"
                MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        val holder = rememberSaveableStateHolder()
                        holder.SaveableStateProvider(if (document.value == null) session else "article") {
                            val path = document.value
                            if (path == null) {
                                GrammarQuizScreen(session, {}, { owner, article, _ ->
                                    assertEquals(source, owner)
                                    document.value = article
                                }, {}, {}, {}, vm = vm)
                            } else {
                                Page("Grammar reference", onBack = { document.value = null }) {
                                    MarkdownView(articles.single { "articles/${it.slug}.md" == path }.body, path,
                                        { document.value = it })
                                }
                            }
                        }
                    }
                }
            }
            rule.waitUntil(15_000) { vm.ui is GrammarUi.Choose }
            val wrongModes = mutableSetOf<String>()
            val correctModes = mutableSetOf<String>()
            var openedArticle = false
            var steps = 0
            while (vm.ui !is GrammarUi.Done && steps++ < selected.size * 3 + 6) {
                val mode = answerQuestion(vm, selected, wrongModes)
                val answer = vm.ui as GrammarUi.Answer
                rule.onNodeWithText(if (answer.correct) "Correct!" else "Not quite").performScrollTo().assertIsDisplayed()
                if (answer.correct) correctModes += mode
                if (!openedArticle) {
                    val before = runBlocking { db.reviews().allLog() }
                    rule.onNodeWithText("Read about: ${vm.articleReference(answer.exercise.id).topic}").performScrollTo().performClick()
                    rule.onNodeWithText("Grammar reference").assertIsDisplayed()
                    rule.onNodeWithContentDescription("Back").performClick()
                    assertSame(answer, vm.ui)
                    assertEquals(before, runBlocking { db.reviews().allLog() })
                    openedArticle = true
                }
                rule.onNodeWithText("Next").performScrollTo().performClick()
                rule.waitUntil(10_000) { vm.ui is GrammarUi.Choose || vm.ui is GrammarUi.Type || vm.ui is GrammarUi.Done }
                rule.waitForIdle()
            }
            assertTrue(vm.ui is GrammarUi.Done)
            assertEquals(setOf("choose", "type"), wrongModes)
            assertEquals(setOf("choose", "type"), correctModes)
            assertTrue(openedArticle)
            runBlocking {
                db.withTransaction {}
                val responses = db.reviews().allGrammarResponses()
                assertTrue(responses.isNotEmpty())
                assertEquals(responses.size, db.reviews().allLog().size)
            }
        } finally {
            rule.runOnIdle { vm.leaveSession() }
            runBlocking { db.withTransaction {} }
            db.close()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    private fun answerQuestion(vm: GrammarQuizViewModel, selected: Set<String>, wrongModes: MutableSet<String>): String =
        when (val state = vm.ui) {
            is GrammarUi.Choose -> {
                assertTrue(state.exercise.id in selected)
                rule.onNodeWithText(if (state.choices.correctOptionIds.size > 1) "Choose any one of the correct answers."
                    else "Choose an answer that fits the task.").performScrollTo().assertIsDisplayed()
                val wrong = wrongModes.add("choose")
                val option = state.options.first { (it.id in state.choices.correctOptionIds) != wrong }
                rule.onNodeWithText(option.label).performScrollTo().performClick()
                assertEquals(!wrong, (vm.ui as GrammarUi.Answer).correct)
                "choose"
            }
            is GrammarUi.Type -> {
                assertTrue(state.exercise.id in selected)
                rule.onNodeWithText("Type the missing text. Follow the task instructions.").performScrollTo().assertIsDisplayed()
                val wrong = wrongModes.add("type")
                val response = if (wrong) "not-an-accepted-answer" else state.exercise.acceptedAnswers.last()
                assertTrue(response.length <= 80)
                rule.onNode(hasSetTextAction()).performScrollTo().performClick()
                rule.onNode(hasSetTextAction()).performTextReplacement(response)
                if (wrong) {
                    rule.waitForIdle()
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        File(instrumentation.targetContext.getExternalFilesDir(null), "grammar-answer-keyboard.png")
                            .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally {
                        image.recycle()
                    }
                }
                rule.onNodeWithText("Check").performScrollTo().assertIsDisplayed().performClick()
                assertEquals(!wrong, (vm.ui as GrammarUi.Answer).correct)
                assertEquals(GrammarChoices.normalizeAnswer(response), (vm.ui as GrammarUi.Answer).responseLabel)
                "type"
            }
            else -> error("Expected a question, got ${vm.ui}")
        }

    private fun fixtureArticles() = listOf(Article("answer-fixture", "Answer fixture",
        "# Answer fixture\n\nSynthetic native UI examples, not published learning content."))

    private fun fixture(whole: Boolean): GrammarSet {
        val targets = listOf("affirmative", "negative")
        val exercises = targets.flatMap { target -> (0..1).map { index ->
            val prefix = if (index == 0) "明日は本を１冊" else "今日は新聞を２部"
            val endings = listOf("読みます", "読みません")
            val labels = endings.map { if (whole) prefix + it else it }
            val wanted = targets.indexOf(target)
            GrammarExercise("answer-$target-$index", target, "polarity",
                if (whole) "語句：〔${endings[wanted]}〕〔${prefix.take(3)}〕〔${prefix.drop(3)}〕\n____。"
                    else "${prefix}____。",
                if (whole) "Use each supplied chunk once. Type the whole sentence without spaces, labels or punctuation."
                    else "Complete the predicate in the $target polite nonpast form of 読む (よむ, read).",
                "The requested response preserves the supplied polarity.", "The answer fills exactly the response slot.",
                target, listOf(labels[wanted]), targets.mapIndexed { i, id ->
                    GrammarOption(id, labels[i], if (i == wanted) "Matches the task." else "Changes the requested polarity.")
                }, 4)
        } }
        return GrammarSet(1, "answer-fixture", "Answer scope fixture", "articles/answer-fixture.md",
            targets.map { GrammarConstruction(it, it) }, listOf(GrammarRelationship("polarity", "Polarity", targets)), exercises)
    }
}
