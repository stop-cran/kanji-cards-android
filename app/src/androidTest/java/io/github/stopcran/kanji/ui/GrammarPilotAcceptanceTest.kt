package io.github.stopcran.kanji.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarCurriculumState
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.content.SafeZip
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.core.srs.CardPhase
import io.github.stopcran.kanji.core.srs.SrsState
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.data.ContentHttp
import io.github.stopcran.kanji.data.ContentResponse
import io.github.stopcran.kanji.data.ContentSync
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarBrowserPreferences
import io.github.stopcran.kanji.data.SyncResult
import io.github.stopcran.kanji.data.toEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random

/** Opt-in candidate acceptance on a disposable emulator; no public upload or real learner source. */
class GrammarPilotAcceptanceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun unclassifiedPilotImportsBrowsesAndAcceptsAlternatives() {
        val args = InstrumentationRegistry.getArguments()
        val setId = args.getString("grammarSet")
        val chapterId = args.getString("grammarChapter")
        assumeTrue("Requires explicit candidate set and chapter", setId != null && chapterId != null)
        assertTrue("Use a disposable emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KanjiApp
        val archive = File(app.getExternalFilesDir(null), "grammar-acceptance.zip")
        assertTrue("Provide the complete candidate archive", archive.isFile)
        val parsed = archive.inputStream().use { ContentParser.parse(SafeZip.read(it)) }
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertTrue(parsed.grammarProblems.toString(), parsed.grammarProblems.isEmpty())
        val set = parsed.grammar.single { it.id == setId }
        assertTrue(set.exercises.all { it.jlpt == null })
        val catalog = (parsed.curriculum as GrammarCurriculumState.Ready).curriculum
        val catalogChapter = catalog.chapters.single { it.id == chapterId }
        val authoredGroups = parsed.grammar.flatMap { it.introductionGroups }.associateBy { it.id }
        val expectedChapterIds = catalogChapter.groups.flatMap { group ->
            authoredGroups.getValue(group).members.map { it.exerciseId }
        }.toSet()
        assertTrue(expectedChapterIds.containsAll(set.exercises.map { it.id }))
        val alternativeExample = set.exercises.first {
            it.additionalCorrectOptions.isNotEmpty() &&
                it.vocabulary?.spans.orEmpty().any { span -> span.word != null && span.text != span.reading }
        }
        val exampleGroup = set.introductionGroups.single { group ->
            group.members.any { it.exerciseId == alternativeExample.id }
        }
        val original = app.settings.source.value
        val oldDaily = app.settings.dailyNewCards.value
        val oldWeek = app.settings.weekPlan.value
        val source = RepoSource("acceptance-fixture", "pilot-${UUID.randomUUID()}", "main")
        val head = "0".repeat(38) + "58"
        val headUrl = "https://api.github.com/repos/${source.id}/commits/${source.branch}"
        val requests = mutableListOf<String>()
        val http = ContentHttp { url, _, _, _ ->
            requests += url
            val input = when (url) {
                headUrl -> ByteArrayInputStream(head.toByteArray())
                source.commitZipUrl(head) -> archive.inputStream()
                else -> error("Unexpected request: $url")
            }
            object : ContentResponse {
                override val code = 200
                override val body = input
                override fun header(name: String): String? = null
                override fun close() = input.close()
            }
        }
        val active = mutableStateOf<Pair<String, GrammarQuizViewModel>?>(null)
        val models = ViewModelStore()
        val evidence = JSONObject().put("set", setId).put("chapter", chapterId)
            .put("transport", "local candidate through production ContentSync; synthetic HEAD")
            .put("host", "production GrammarPage and GrammarQuizScreen, not MainActivity/AppNav")
            .put("choiceRandom", "zero bits to exercise the real additional-choice branch deterministically")
        fun open(target: GrammarPracticeTarget) {
            active.value?.second?.leaveSession()
            val route = GrammarPracticeRoutes.create(target)
            val vm = GrammarQuizViewModel(app.services, object : Random() {
                override fun nextBits(bitCount: Int) = 0
            }, target)
            models.put(route, vm)
            active.value = route to vm
        }
        fun awaitPreview(kind: GrammarTargetKind): GrammarUi.Preview {
            rule.waitUntil(15_000) { active.value?.second?.ui is GrammarUi.Preview }
            return (active.value!!.second.ui as GrammarUi.Preview).also { assertEquals(kind, it.preview.target.kind) }
        }
        fun click(text: String) = rule.onNodeWithText(text).performScrollTo().assertIsDisplayed().performClick()
        fun clickTopic(text: String) {
            rule.onNodeWithTag("grammar-list").performScrollTo().performScrollToNode(hasText(text))
            click(text)
        }
        fun capture(name: String) {
            File(app.getExternalFilesDir(null), name).outputStream().use {
                rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        try {
            assertTrue(app.settings.setSource("https://github.com/${source.id}", source.branch))
            app.settings.setDailyNewCards(50)
            app.settings.setWeekPlan("FFFFFFF")
            app.settings.setGrammarNewScope(source.id, GrammarScope.N5)
            app.settings.setGrammarBrowseScope(source.id, GrammarScope.N5)
            app.settings.setGrammarBrowser(source, GrammarBrowserPreferences(GrammarTargetKind.Chapter, false))
            val synced = runBlocking { ContentSync(app.db, app.settings.sync, http).sync(source, force = true) }
            assertTrue(synced.toString(), synced is SyncResult.Updated)
            assertTrue((synced as SyncResult.Updated).problems.toString(), synced.problems.isEmpty())
            assertEquals(listOf(headUrl, source.commitZipUrl(head)), requests)
            evidence.put("requests", JSONArray(requests)).put("syncGrammarCount", synced.grammar)
            rule.setContent {
                KanjiTheme {
                    val holder = rememberSaveableStateHolder()
                    val current = active.value
                    holder.SaveableStateProvider(current?.first ?: "home") {
                        if (current == null) GrammarPage(
                            app, {}, { _, _, _ -> }, {}, ::open,
                        ) else GrammarQuizScreen(
                            current.first, { active.value = null }, { _, _, _ -> },
                            { open(requireNotNull(current.second.target)) }, ::open, { active.value = null },
                            target = current.second.target, vm = current.second,
                        )
                    }
                }
            }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("Browse Grammar").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(catalogChapter.label).assertDoesNotExist()
            rule.onNodeWithText("Unclassified").onParent().performScrollTo()
            click("Unclassified")
            rule.waitUntil(10_000) {
                app.settings.grammarPreferences.value[source.id]?.newScope == GrammarScope.Unclassified
            }
            rule.onNodeWithText(catalogChapter.label).assertDoesNotExist()
            click("Filters and next topic")
            val browseScope = rule.onAllNodesWithText("Unclassified")[1]
            browseScope.onParent().performScrollTo()
            browseScope.performScrollTo().assertIsDisplayed().performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("Browse: Unclassified").fetchSemanticsNodes().isNotEmpty()
            }
            clickTopic(catalogChapter.label)
            val chapter = awaitPreview(GrammarTargetKind.Chapter).preview
            assertEquals(chapterId, chapter.target.id)
            assertEquals(expectedChapterIds, chapter.ids(false).toSet())
            assertTrue(chapter.members.none { it.started || it.outOfScope || it.missingPrerequisites.isNotEmpty() })
            evidence.put("chapterEligible", JSONArray(chapter.ids(false))).put("independentBrowseFilter", true)
            val groupId = exampleGroup.id
            assertTrue(groupId in catalogChapter.groups)
            // Make one actual candidate due so shuffled fresh intake cannot hide the linked alternative.
            val now = Instant.now()
            runBlocking {
                app.db.reviews().putState(
                    SrsState(CardPhase.Review, 30.0, 5.0, now.minus(1, ChronoUnit.DAYS),
                        now.minus(2, ChronoUnit.DAYS), 3, 0)
                        .toEntity(source.id, GRAMMAR_STACK, alternativeExample.id, StudyMode.Grammar),
                )
            }
            evidence.put("fixtureDueExercise", alternativeExample.id)
            rule.onNodeWithText("Accessible list").onParent().performScrollTo()
            click("Accessible list")
            clickTopic(chapter.groups.getValue(groupId))
            val group = awaitPreview(GrammarTargetKind.Group).preview
            assertEquals(groupId, group.target.id)
            assertEquals(exampleGroup.members.map { it.exerciseId }.toSet(), group.ids(false).toSet())
            evidence.put("group", groupId).put("groupEligible", JSONArray(group.ids(false)))
            click("Practice")
            rule.waitUntil(10_000) { active.value?.second?.ui is GrammarUi.Choose }
            val vm = active.value!!.second
            assertEquals(alternativeExample.id, vm.currentExercise!!.id)
            val before = vm.ui
            val span = vm.currentExercise!!.vocabulary!!.spans.first { it.word != null && it.text != it.reading }
            click("Vocabulary")
            rule.onNodeWithText(span.text + " \u2014 not yet familiar; look up").performScrollTo().performClick()
            rule.onNodeWithText(span.reading).assertIsDisplayed()
            rule.onNodeWithText(span.gloss).assertIsDisplayed()
            rule.onNodeWithText("Read word article").performClick()
            rule.waitUntil(10_000) { vm.lookupArticle != null }
            rule.onNodeWithContentDescription("Back").performClick()
            rule.waitUntil(10_000) { vm.lookupArticle == null }
            rule.onNodeWithText("Close").performClick()
            rule.waitUntil(10_000) { vm.lookup == null }
            assertSame(before, vm.ui)
            evidence.put("hint", JSONObject().put("text", span.text).put("reading", span.reading).put("word", span.word))
            var alternativeSeen = false
            var attempts = 0
            while (!alternativeSeen && attempts++ < group.members.size) {
                val question = vm.ui
                when (question) {
                    is GrammarUi.Choose -> {
                        val correct = question.options.filter { it.id in question.choices.correctOptionIds }
                        val selected = correct.firstOrNull { it.id != question.exercise.answer } ?: correct.single()
                        if (correct.size > 1) {
                            rule.onNodeWithText("Choose any one of the correct answers.").performScrollTo().assertIsDisplayed()
                            alternativeSeen = true
                        }
                        click(selected.label)
                        rule.waitUntil(10_000) { vm.ui is GrammarUi.Answer }
                        val answer = vm.ui as GrammarUi.Answer
                        assertTrue(answer.correct)
                        if (alternativeSeen) {
                            assertTrue(selected.id != question.exercise.answer)
                            assertEquals(selected.label, answer.completion)
                            rule.onNodeWithText(question.exercise.prompt.replace("____", selected.label))
                                .performScrollTo().assertIsDisplayed()
                            correct.forEach { option ->
                                rule.onNodeWithText("\u2713 Correct: ${option.label} \u2014", substring = true)
                                    .performScrollTo().assertIsDisplayed()
                            }
                            evidence.put("additionalChoice", JSONObject().put("exercise", question.exercise.id)
                                .put("selectedId", selected.id).put("completion", answer.completion)
                                .put("correctIds", JSONArray(answer.correctOptionIds.toList())))
                            capture("grammar-pilot-alternatives.png")
                        }
                    }
                    is GrammarUi.Type -> {
                        rule.onNode(hasSetTextAction()).performTextReplacement(question.exercise.acceptedAnswers.last())
                        click("Check")
                        rule.waitUntil(10_000) { vm.ui is GrammarUi.Answer }
                        assertTrue((vm.ui as GrammarUi.Answer).correct)
                    }
                    else -> error("Unexpected question: $question")
                }
                if (!alternativeSeen) {
                    click("Next")
                    rule.waitUntil(10_000) { vm.ui is GrammarUi.Choose || vm.ui is GrammarUi.Type || vm.ui is GrammarUi.Done }
                }
            }
            assertTrue("No additional correct choice observed", alternativeSeen)
            rule.runOnIdle { vm.leaveSession(); active.value = null }
            rule.waitUntil(15_000) {
                rule.onAllNodesWithText("Article sets").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Article sets").onParent().performScrollTo()
            click("Article sets")
            clickTopic(set.title)
            val setPreview = awaitPreview(GrammarTargetKind.Set).preview
            assertEquals(setId, setPreview.target.id)
            assertEquals(set.exercises.map { it.id }.toSet(), setPreview.members.map { it.exercise.id }.toSet())
            evidence.put("setMembers", JSONArray(setPreview.members.map { it.exercise.id })).put("passed", true)
            capture("grammar-pilot-set-entry.png")
        } catch (error: Throwable) {
            evidence.put("failure", error.toString()).put("preferences", app.settings.grammarFor(source.id).toString())
            runCatching { capture("grammar-pilot-failure.png") }
            runCatching {
                File(app.getExternalFilesDir(null), "grammar-pilot-failure-tree.txt").writeText(rule.onRoot().printToString())
            }
            throw error
        } finally {
            try {
                File(app.getExternalFilesDir(null), "grammar-pilot-evidence.json").writeText(evidence.toString(2))
                rule.runOnIdle { active.value?.second?.leaveSession(); active.value = null; models.clear() }
            } finally {
                app.settings.setDailyNewCards(oldDaily)
                app.settings.setWeekPlan(oldWeek)
                app.settings.setSource("https://github.com/${original.id}", original.branch)
            }
        }
    }
}
