package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.grammarFixture
import io.github.stopcran.kanji.core.familiarEvents
import io.github.stopcran.kanji.core.recommending
import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.GrammarVocabularyData
import io.github.stopcran.kanji.core.content.GrammarVocabularySpan
import io.github.stopcran.kanji.core.content.RepoSource
import io.github.stopcran.kanji.core.grammar.GRAMMAR_LEVEL_SYNC_NEEDED
import io.github.stopcran.kanji.core.grammar.GrammarScope
import io.github.stopcran.kanji.core.home.ModeState
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.GRAMMAR_LEVEL_EPOCH
import io.github.stopcran.kanji.data.GRAMMAR_STACK
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.GrammarResponseEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.StudySessionEntity
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.ProgressExport
import io.github.stopcran.kanji.data.grammarSets
import io.github.stopcran.kanji.data.grammarStudy
import io.github.stopcran.kanji.data.changeGrammarGuided
import io.github.stopcran.kanji.core.grammar.GrammarCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class GrammarQuizViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val sourceId get() = services.settings.source.value.id

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setWeekPlan("FFFFFFF")
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun start(count: Int = 3, answers: List<String> = listOf("model", "alternative")): GrammarQuizViewModel {
        install(count, answers)
        return launch().also { assertTrue("Expected a first choice question, got ${it.ui}", it.ui is GrammarUi.Choose) }
    }

    private fun install(
        count: Int,
        answers: List<String> = listOf("model", "alternative"),
        levels: List<Int?> = List(count) { null },
    ) {
        val constructions = (0 until count).map { GrammarConstruction("target-$it", "Target $it") }
        val exercises = constructions.mapIndexed { index, target ->
            GrammarExercise(
                "exercise-$index", target.id, "contrast", "Complete ____.", "Synthetic grading fixture.",
                "Evidence.", "Explanation.", target.id, answers,
                listOf(
                    GrammarOption(target.id, answers.first(), "Fits."),
                    GrammarOption(constructions[(index + 1) % count].id, "wrong", "Does not fit."),
                ),
                jlpt = levels[index],
            )
        }
        val set = GrammarSet(
            1, "fixture", "Fixture", "articles/fixture.md", constructions,
            listOf(GrammarRelationship("contrast", "Contrast", constructions.map { it.id })), exercises,
        )
        runBlocking {
            db.content().insertGrammar(listOf(GrammarEntity(sourceId, set.id, Json.encodeToString(GrammarSet.serializer(), set), 0)))
        }
    }

    private fun launch(extra: Boolean = false): GrammarQuizViewModel {
        val vm = GrammarQuizViewModel(services)
        vm.ensureStarted(extra)
        val end = System.currentTimeMillis() + 10_000
        while (vm.ui is GrammarUi.Loading && System.currentTimeMillis() < end) Thread.sleep(10)
        assertFalse("Session did not finish loading", vm.ui is GrammarUi.Loading)
        return vm
    }

    private fun mode(): ModeState = runBlocking {
        db.grammarStudy(services.settings, sourceId, Instant.now()).mode
    }

    private fun seedReview(index: Int, due: Instant) = runBlocking {
        val yesterday = Instant.now().minusSeconds(86_400).toEpochMilli()
        db.reviews().recordGrammar(
            ReviewStateEntity(sourceId, GRAMMAR_STACK, "exercise-$index", "Grammar", "Review", 3.0, 5.0, due.toEpochMilli(), yesterday, 1, 0),
            ReviewLogEntity(0, sourceId, GRAMMAR_STACK, "exercise-$index", "Grammar", 3, yesterday),
            GrammarResponseEntity(0, sourceId, GRAMMAR_STACK, "exercise-$index", "target-$index", "contrast", "choose", "target-$index", true),
        )
    }

    private fun answerCorrect(vm: GrammarQuizViewModel) {
        when (val question = vm.ui) {
            is GrammarUi.Choose -> vm.pick(question.exercise.answer)
            is GrammarUi.Type -> {
                vm.onDraft(question.exercise.acceptedAnswers.last())
                vm.check()
            }
            else -> error("Expected a question, got $question")
        }
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
    }

    private fun advance(vm: GrammarQuizViewModel, guessed: Boolean = false) {
        vm.next(guessed)
        // Drain Room's serial transaction queue even when this answer should not have recorded anything.
        runBlocking { withTimeout(10_000) { db.withTransaction {} } }
    }

    private fun logs() = runBlocking { db.reviews().allLog() }
    private fun details() = runBlocking { db.reviews().allGrammarResponses() }
    private fun sessions() = runBlocking { db.reviews().allSessions() }

    private fun awaitSessionEnd(id: String): StudySessionEntity {
        val deadline = System.currentTimeMillis() + 10_000
        var row = sessions().single { it.id == id }
        while (row.endedAtMs == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            row = sessions().single { it.id == id }
        }
        assertTrue("Session did not close", row.endedAtMs != null)
        return row
    }
    private fun states() = runBlocking { db.reviews().allStates() }

    private fun vocabularyFixture(): List<GrammarVocabularySpan> {
        install(3)
        val forms = listOf("Known", "OtherMode", "OtherSource", "Weak", "Unstarted", "Local")
        val prompt = forms.joinToString(" ") + " ____ Known."
        val spans = (forms + "Known").mapIndexed { index, text ->
            val at = if (index == forms.size) prompt.lastIndexOf(text) else prompt.indexOf(text)
            GrammarVocabularySpan(at, at + text.length, text, text.lowercase(), text, "Context for $text",
                word = text.takeUnless { it == "Local" })
        }
        runBlocking {
            val base = db.grammarSets(sourceId).single()
            val set = base.copy(exercises = base.exercises.map { e ->
                e.copy(prompt = prompt, vocabulary = GrammarVocabularyData(1, ContentParser.rawSha256(prompt.toByteArray()), spans))
            })
            db.content().insertGrammar(listOf(GrammarEntity(sourceId, set.id, Json.encodeToString(GrammarSet.serializer(), set), 0)))
            db.content().insertWords(forms.filterNot { it == "Local" }.map {
                WordEntity(sourceId, it, it.lowercase(), "Original $it", null, "", "", "Original article for $it")
            })
            val yesterday = Instant.now().minusSeconds(86_400).toEpochMilli()
            val other = RepoSource("example", "fork", "main").id
            for (state in listOf(
                ReviewStateEntity(sourceId, "n5", "Known", "WordJpEn", "Review", 1.0, 5.0, yesterday, yesterday, 3, 0),
                ReviewStateEntity(sourceId, "custom", "Known", "WordJpEn", "Review", 14.0, 5.0, yesterday, yesterday, 3, 0),
                ReviewStateEntity(sourceId, "n5", "OtherMode", "WordEnJp", "Review", 14.0, 5.0, yesterday, yesterday, 3, 0),
                ReviewStateEntity(other, "n5", "OtherSource", "WordJpEn", "Review", 14.0, 5.0, yesterday, yesterday, 3, 0),
                ReviewStateEntity(sourceId, "n5", "Weak", "WordJpEn", "Review", 1.0, 5.0, yesterday, yesterday, 3, 0),
                ReviewStateEntity(sourceId, "n5", "Local", "Quiz", "Review", 14.0, 5.0, yesterday, yesterday, 3, 0),
            )) {
                db.reviews().record(
                    state,
                    ReviewLogEntity(0, state.sourceId, state.stack, state.kanji, state.mode, 3, yesterday),
                )
            }
        }
        return spans
    }

    private fun exportedProgress(): Map<String, String> = runBlocking {
        val bytes = ByteArrayOutputStream()
        ProgressExport.write(db, services.settings, bytes)
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        entries
    }

    @Test
    fun lookupPreservesChoiceDraftAnswerAndExportedSessionWithoutProgressMutations() {
        val spans = vocabularyFixture()
        val vm = launch()
        val initialSession = Json.parseToJsonElement(exportedProgress().getValue("study_sessions.json")).jsonArray.single().jsonObject
        val sessionId = initialSession.getValue("id").jsonPrimitive.content
        fun lookAround() {
            val ui = vm.ui
            val draft = vm.draft
            val before = exportedProgress()
            val focus = runBlocking { db.reviews().grammarFocus(sourceId) }
            vm.openVocabularyList()
            vm.closeVocabularyList()
            vm.openLookup(spans.first())
            assertTrue(vm.lookupHasArticle())
            vm.openLookupArticle()
            assertEquals("Original article for Known", vm.lookupArticle!!.body)
            vm.closeLookupArticle()
            vm.closeLookup()
            vm.openLookup(spans.first { it.word == null })
            assertFalse(vm.lookupHasArticle())
            vm.closeLookup()
            assertEquals(ui, vm.ui)
            assertEquals(draft, vm.draft)
            assertEquals(before, exportedProgress())
            assertEquals(focus, runBlocking { db.reviews().grammarFocus(sourceId) })
        }
        lookAround()
        vm.pick((vm.ui as GrammarUi.Choose).exercise.answer)
        lookAround()
        advance(vm)
        assertTrue(vm.ui is GrammarUi.Type)
        vm.onDraft("alternative")
        lookAround()
        vm.check()
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        lookAround()
        advance(vm)
        vm.leaveSession()
        val finished = awaitSessionEnd(sessionId)
        assertEquals(2, finished.answered)
        assertEquals(2, finished.correct)
        val exported = exportedProgress()
        assertEquals(sessionId, Json.parseToJsonElement(exported.getValue("study_sessions.json"))
            .jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val grammarLogs = Json.parseToJsonElement(exported.getValue("review_log.json")).jsonArray
            .map { it.jsonObject }.filter { it.getValue("mode").jsonPrimitive.content == "Grammar" }
        assertEquals(2, grammarLogs.size)
        assertTrue(grammarLogs.all { it.getValue("sessionId").jsonPrimitive.content == sessionId })
    }

    @Test
    fun familiarityAndWordArticlesAreSourceScopedSnapshotsAcrossSyncAndSourceChanges() {
        val spans = vocabularyFixture()
        val vm = launch()
        assertEquals(listOf(true, false, false, false, false, false, true), spans.map(vm::isFamiliar))
        val capturedSource = sourceId
        val question = vm.ui
        runBlocking {
            val known = db.content().word(capturedSource, "Known")!!
            val weak = db.content().word(capturedSource, "Weak")!!
            db.content().insertWords(listOf(
                known.copy(body = "Changed by sync"),
                known.copy(sourceId = RepoSource("example", "fork", "main").id, body = "Other source article"),
            ))
            val now = Instant.now().toEpochMilli()
            db.reviews().record(
                ReviewStateEntity(capturedSource, "custom", weak.word, "WordJpEn", "Review", 14.0, 5.0, now, now, 5, 0),
                ReviewLogEntity(0, capturedSource, "custom", weak.word, "WordJpEn", 3, now),
            )
            val old = db.grammarSets(capturedSource).single()
            val changed = old.copy(exercises = old.exercises.map { it.copy(prompt = "Changed ____.", vocabulary = null) })
            db.content().insertGrammar(listOf(GrammarEntity(capturedSource, changed.id, Json.encodeToString(GrammarSet.serializer(), changed), 0)))
        }
        assertTrue(services.settings.setSource("https://github.com/example/fork", "main"))
        vm.ensureStarted(false)
        vm.openLookup(spans.first())
        vm.openLookupArticle()
        assertEquals("Original article for Known", vm.lookupArticle!!.body)
        assertEquals(capturedSource, vm.lookupArticle!!.sourceId)
        assertEquals(capturedSource, vm.sourceId)
        assertEquals(question, vm.ui)
        assertEquals(listOf(true, false, false, false, false, false, true), spans.map(vm::isFamiliar))
        vm.closeLookupArticle()
        vm.closeLookup()
        vm.leaveSession()
        awaitSessionEnd(sessions().single().id)
    }

    @Test
    fun repeatedNextCallbackCannotRecordOrCommitAFocusTwice() {
        install(4)
        services.settings.setGrammarParserEpoch(sourceId, ContentParser.GRAMMAR_PARSER_EPOCH)
        runBlocking { db.changeGrammarGuided(services.settings, sourceId, true) }
        val vm = launch()
        val question = vm.ui as GrammarUi.Choose
        vm.pick(question.exercise.answer)
        advance(vm)
        advance(vm)
        assertEquals(1, logs().size)
        assertEquals(1, details().size)
        val row = runBlocking { db.reviews().grammarFocus(sourceId) }!!
        val cursor = Json.decodeFromString(GrammarCursor.serializer(), row.json)
        assertEquals(2L, cursor.automaticOrdinal)
        assertEquals(1, cursor.active!!.introduced.size)
    }

    @Test
    fun rankedSessionMatchesHomeMixesDueReviewsAndCommitsOnlyItsBoundedNewGroup() {
        val foundation = grammarFixture("foundation", examples = 2).recommending("beta" to 3).let { set ->
            set.copy(exercises = set.exercises.map { it.copy(jlpt = 3) })
        }
        val sets = listOf(foundation, grammarFixture("beta"), grammarFixture("alpha"))
        services.settings.setGrammarParserEpoch(sourceId, ContentParser.GRAMMAR_PARSER_EPOCH)
        services.settings.setGrammarNewScope(sourceId, GrammarScope.N4)
        runBlocking {
            db.content().insertGrammar(sets.mapIndexed { index, set ->
                GrammarEntity(sourceId, set.id, Json.encodeToString(GrammarSet.serializer(), set), index)
            })
            familiarEvents(foundation, Instant.now()).forEach { event ->
                db.reviews().recordGrammar(
                    ReviewStateEntity(sourceId, GRAMMAR_STACK, event.exerciseId, "Grammar", "Review", 3.0, 5.0, event.atMs, event.atMs, 1, 0),
                    ReviewLogEntity(0, sourceId, GRAMMAR_STACK, event.exerciseId, "Grammar", event.grade, event.atMs),
                    GrammarResponseEntity(
                        0, sourceId, GRAMMAR_STACK, event.exerciseId, event.constructionId!!, event.relationshipId!!,
                        event.promptType!!, event.constructionId!!, true,
                    ),
                )
            }
            db.changeGrammarGuided(services.settings, sourceId, true)
        }
        assertEquals(ModeState.Start(4, 4), mode())
        val vm = launch()
        assertEquals("beta", vm.focus!!.current!!.group.id)
        assertFalse(vm.focusNotice.orEmpty().contains(vm.focus!!.reason))
        val asked = mutableListOf<String>()
        repeat(8) {
            asked += when (val question = vm.ui) {
                is GrammarUi.Choose -> question.exercise.id
                is GrammarUi.Type -> question.exercise.id
                else -> error("Expected one of the eight queued questions, got $question")
            }
            answerCorrect(vm)
        }
        assertTrue(vm.ui is GrammarUi.Done)
        assertEquals(4, asked.count { it.startsWith("foundation-") })
        assertEquals(4, asked.count { it.startsWith("beta-") })
        val cursor = runBlocking { db.reviews().grammarFocus(sourceId) }!!.let { Json.decodeFromString(GrammarCursor.serializer(), it.json) }
        assertEquals(2L, cursor.automaticOrdinal)
        assertEquals(mapOf("a" to 2, "b" to 2), cursor.active!!.introduced.groupingBy { it.facetId }.eachCount())
        assertEquals(12, logs().size)
        assertEquals(12, details().size)
        val more = runBlocking { db.grammarStudy(services.settings, sourceId, Instant.now(), extra = true) }
        assertEquals(16, more.extraIds.size)
        assertTrue(more.extraIds.any { it.startsWith("alpha-") })
        assertEquals(null, more.focus)
    }

    @Test
    fun singleEligibleExerciseIsAskableAndChangingScopeDoesNotChangeTheSessionSnapshot() {
        install(3, levels = listOf(3, 4, null))
        services.settings.setGrammarParserEpoch(sourceId, GRAMMAR_LEVEL_EPOCH)
        services.settings.setGrammarNewScope(sourceId, GrammarScope.N4)
        services.settings.setGrammarBrowseScope(sourceId, GrammarScope.N3)
        assertEquals(ModeState.Start(0, 1), mode())
        val vm = launch()
        assertEquals("exercise-1", (vm.ui as GrammarUi.Choose).exercise.id)
        services.settings.setGrammarNewScope(sourceId, GrammarScope.Unclassified)
        answerCorrect(vm)
        assertTrue(vm.ui is GrammarUi.Done)
        assertEquals(listOf("exercise-1"), states().map { it.kanji })
        assertEquals(GrammarScope.N3, services.settings.grammarFor(sourceId).browseScope)
        assertEquals("exercise-2", (launch().ui as GrammarUi.Choose).exercise.id)
        assertEquals(1, logs().size)
    }

    @Test
    fun emptyNewScopeStillOffersOutOfScopeDueReviews() {
        install(3, levels = listOf(3, 4, null))
        services.settings.setGrammarParserEpoch(sourceId, GRAMMAR_LEVEL_EPOCH)
        services.settings.setGrammarNewScope(sourceId, GrammarScope.N5)
        assertTrue(mode() is ModeState.Locked)
        assertTrue((launch().ui as GrammarUi.Empty).message.contains("No unseen grammar exercises match N5"))
        seedReview(0, Instant.now().minusSeconds(60))
        assertEquals(ModeState.Start(1, 0), mode())
        assertEquals("exercise-0", (launch().ui as GrammarUi.Choose).exercise.id)
    }

    @Test
    fun extraPracticeFiltersUnseenItemsButKeepsEarlyReviewsAndIgnoresTheDailyCap() {
        install(4, levels = listOf(4, 3, null, 5))
        services.settings.setGrammarParserEpoch(sourceId, GRAMMAR_LEVEL_EPOCH)
        services.settings.setGrammarNewScope(sourceId, GrammarScope.N4)
        services.settings.setDailyNewCards(0)
        seedReview(1, Instant.now().plusSeconds(86_400))
        assertEquals(ModeState.PracticeMore, mode())
        val vm = launch(extra = true)
        val ids = mutableListOf<String>()
        repeat(3) {
            ids += when (val question = vm.ui) {
                is GrammarUi.Choose -> question.exercise.id
                is GrammarUi.Type -> question.exercise.id
                else -> error("Expected an extra-practice question, got $question")
            }
            answerCorrect(vm)
        }
        assertEquals(setOf("exercise-0", "exercise-3"), ids.take(2).toSet())
        assertEquals("exercise-1", ids.last())
        assertTrue(vm.ui is GrammarUi.Done)
        assertEquals(setOf("exercise-0", "exercise-1", "exercise-3"), states().map { it.kanji }.toSet())
    }

    @Test
    fun pendingEpochBlocksRestrictedNewItemsButNotAllOrStartedExtraPractice() {
        install(3)
        seedReview(0, Instant.now().plusSeconds(86_400))
        val before = Triple(states(), logs(), details())
        for (scope in listOf(GrammarScope.Unclassified, GrammarScope.N4)) {
            services.settings.setGrammarNewScope(sourceId, scope)
            assertEquals(ModeState.PracticeMore, mode())
            assertEquals(GRAMMAR_LEVEL_SYNC_NEEDED, (launch().ui as GrammarUi.Empty).message)
            assertEquals("exercise-0", (launch(extra = true).ui as GrammarUi.Choose).exercise.id)
        }
        services.settings.setGrammarNewScope(sourceId, GrammarScope.All)
        assertEquals(ModeState.Start(0, 2), mode())
        assertTrue(launch().ui is GrammarUi.Choose)
        assertEquals(before, Triple(states(), logs(), details()))
    }

    @Test
    fun articleReferencesCaptureDistinctTopicsBeforeContentAndSettingsChange() {
        install(3)
        val owner = sourceId
        val row = runBlocking { db.content().grammar(owner).single() }
        val original = Json.decodeFromString(GrammarSet.serializer(), row.json)
        val set = original.copy(
            relationships = listOf(
                original.relationships.single().copy(id = "alpha", label = "Topic: alpha / one"),
                original.relationships.single().copy(id = "beta", label = "Topic: beta / two"),
            ),
            exercises = original.exercises.mapIndexed { index, exercise -> exercise.copy(relationship = if (index == 0) "alpha" else "beta") },
        )
        runBlocking { db.content().insertGrammar(listOf(row.copy(json = Json.encodeToString(GrammarSet.serializer(), set)))) }
        val vm = launch()
        val first = GrammarArticleReference("articles/fixture.md", "Topic: alpha / one")
        val second = GrammarArticleReference("articles/fixture.md", "Topic: beta / two")
        assertEquals(first, vm.articleReference("exercise-0"))
        assertEquals(second, vm.articleReference("exercise-1"))
        val changed = set.copy(article = "articles/changed.md", relationships = set.relationships.map { it.copy(label = "Changed") })
        runBlocking { db.content().insertGrammar(listOf(row.copy(json = Json.encodeToString(GrammarSet.serializer(), changed)))) }
        assertTrue(services.settings.setSource("https://github.com/fixture/other", "main"))
        assertEquals(owner, vm.sourceId)
        assertEquals(first, vm.articleReference("exercise-0"))
        assertEquals(second, vm.articleReference("exercise-1"))
    }

    @Test
    fun choiceAndTypingRecordGoodAndGuessedChoiceRecordsHard() {
        val vm = start()
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.exercise.answer)
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        assertTrue(logs().isEmpty())
        assertEquals("articles/fixture.md", vm.articleReference(first.exercise.id).path)
        advance(vm)
        answerCorrect(vm)
        val last = vm.ui as GrammarUi.Choose
        vm.pick(last.exercise.answer)
        advance(vm, guessed = true)
        assertTrue(vm.ui is GrammarUi.Done)
        assertEquals(listOf(3, 3, 2), logs().map { it.grade })
        assertEquals(listOf("choose", "type", "choose"), details().map { it.promptType })
        assertEquals(listOf(first.exercise.answer, "alternative", last.exercise.answer), details().map { it.response })
        assertTrue(details().all { it.correct })
        val byId = logs().associateBy { it.id }
        details().forEach {
            val log = byId.getValue(it.reviewLogId)
            assertEquals(it.exerciseId, log.kanji)
            assertEquals(it.sourceId, log.sourceId)
            assertEquals(GRAMMAR_STACK, log.stack)
            assertEquals("Grammar", log.mode)
            assertEquals("", log.reading)
        }
        assertEquals(3, states().size)
    }

    @Test
    fun typingTrimsAndNfkcNormalizesBothInputAndAcceptedAlternatives() {
        val vm = start(2, listOf("ＭＯＤＥＬ", "  ＡＬＴＥＲＮＡＴＥ  "))
        answerCorrect(vm)
        vm.onDraft(" \u3000 ")
        vm.check()
        assertTrue(vm.ui is GrammarUi.Type)
        assertEquals(1, logs().size)
        vm.onDraft(" \u3000ＡLＴＥＲＮＡＴＥ\u3000 ")
        vm.check()
        assertTrue((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        assertEquals("ALTERNATE", details().last().response)
        assertEquals(3, logs().last().grade)
    }

    @Test
    fun typingDoesNotAcceptAnUnlistedExtension() {
        val vm = start(2)
        answerCorrect(vm)
        vm.onDraft("model with extra words")
        vm.check()
        assertFalse((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        assertEquals(1, logs().last().grade)
        assertFalse(details().last().correct)
        assertEquals("model with extra words", details().last().response)
    }

    @Test
    fun givingUpInEitherPresentationRecordsAnEmptyAgainNotTheDraft() {
        val vm = start(2)
        vm.pick(null)
        assertFalse((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        vm.onDraft("alternative")
        vm.check(giveUp = true)
        assertFalse((vm.ui as GrammarUi.Answer).correct)
        advance(vm)
        assertEquals(listOf(1, 1), logs().map { it.grade })
        assertEquals(listOf("choose", "type"), details().map { it.promptType })
        assertTrue(details().all { !it.correct && it.response.isEmpty() })
    }

    @Test
    fun wrongChoiceCanReturnAsTypingWithoutCreatingAnotherSchedule() {
        val vm = start()
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.options.first { it.id != first.exercise.answer }.id)
        advance(vm)
        repeat(2) { answerCorrect(vm) }
        val repeat = vm.ui as GrammarUi.Type
        assertEquals(first.exercise.id, repeat.exercise.id)
        answerCorrect(vm)
        assertTrue(vm.ui is GrammarUi.Done)
        val repeated = details().filter { it.exerciseId == first.exercise.id }
        assertEquals(listOf("choose", "type"), repeated.map { it.promptType })
        assertEquals(listOf(false, true), repeated.map { it.correct })
        assertTrue(repeated.all { it.constructionId == first.exercise.target && it.relationshipId == first.exercise.relationship })
        assertEquals(4, logs().size)
        assertEquals(3, states().size)
        assertEquals(2, states().single { it.kanji == first.exercise.id }.reps)
    }

    @Test
    fun successfulPracticeConfirmationAddsNeitherLogNorResponseNorSrsRepetition() {
        val vm = start(5)
        val opened = sessions().single()
        vm.ensureStarted(false)
        assertEquals(listOf(opened), sessions())
        val first = vm.ui as GrammarUi.Choose
        vm.pick(first.options.first { it.id != first.exercise.answer }.id)
        advance(vm)
        repeat(6) { answerCorrect(vm) }
        val done = vm.ui as GrammarUi.Done
        assertEquals(7, done.answered)
        assertEquals(6, done.correct)
        assertEquals(6, logs().size)
        assertEquals(6, details().size)
        assertEquals(2, details().count { it.exerciseId == first.exercise.id })
        assertEquals(2, states().single { it.kanji == first.exercise.id }.reps)
        val closed = awaitSessionEnd(opened.id)
        assertEquals("completed", closed.endReason)
        assertEquals(done.answered, closed.answered)
        assertEquals(done.correct, closed.correct)
        assertFalse(closed.extra)
        assertEquals(setOf(closed.id), logs().map { it.sessionId }.toSet())
        vm.leaveSession()
        assertEquals(closed, sessions().single())
    }

    @Test
    fun leavingKeepsPendingReviewsAndExtraPracticeStartsANewSession() {
        val vm = start(3)
        val opened = sessions().single()
        services.studySessions.register("grammar-route", vm::leaveSession)
        services.studySessions.leave("doc:articles/fixture.md")
        assertNull(sessions().single().endReason)
        val first = vm.ui as GrammarUi.Choose
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = launch(Dispatchers.IO) {
                db.withTransaction {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                withTimeout(10_000) { entered.await() }
                vm.pick(first.exercise.answer)
                vm.next()
                services.studySessions.leave("grammar-route")
            } finally {
                release.complete(Unit)
            }
            blocker.join()
        }
        val closed = awaitSessionEnd(opened.id)
        assertEquals("left", closed.endReason)
        assertEquals(1, closed.answered)
        assertEquals(1, closed.correct)
        assertEquals(opened.id, logs().single().sessionId)
        assertEquals(first.exercise.id, logs().single().kanji)
        assertTrue(logs().single().atMs >= opened.startedAtMs)
        assertTrue(logs().single().atMs <= closed.endedAtMs!!)
        val extra = launch(extra = true)
        val next = sessions().single { it.id != opened.id }
        assertNotEquals(opened.id, next.id)
        assertTrue(next.extra)
        assertNull(next.endReason)
        extra.leaveSession()
        assertEquals("left", awaitSessionEnd(next.id).endReason)
    }
}
