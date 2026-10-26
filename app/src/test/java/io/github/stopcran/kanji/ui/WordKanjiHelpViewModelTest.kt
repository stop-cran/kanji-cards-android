package io.github.stopcran.kanji.ui

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordStacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ImageEntity
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewLogEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import io.github.stopcran.kanji.data.WordSceneEntity
import io.github.stopcran.kanji.data.newAllowance
import io.github.stopcran.kanji.data.joinSep
import io.github.stopcran.kanji.data.sceneWebp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WordKanjiHelpViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var services: Services
    private val source get() = services.settings.source.value.id
    private val stack = WordStacks.n5.id

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context), context)
        services.settings.setWeekPlan("FFFFFFF")
        services.settings.setN4Unlocked(true)
        services.settings.setWordStack(stack)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun install() = runBlocking {
        db.content().insertKanji(mapOf("音" to 5, "開" to 4, "難" to 3, "京" to null).map { (kanji, level) ->
            KanjiEntity(source, kanji, "Character meaning", level, "", null, null, null, "", "", "", "", null, 0)
        })
        for ((index, word) in listOf("卵音", "難音", "開音", "京音").withIndex()) {
            val image = "images/word-$index.webp"
            val bytes = sceneWebp(index)
            db.content().insertWords(listOf(
                WordEntity(source, word, "よみ$index", "Meaning $index", "wago", word.map { it.toString() }.joinSep(),
                    "onomatopoeia", "Secret article", 5,
                    image = image, imageScene = "Scene"),
            ))
            db.content().insertImages(listOf(ImageEntity(source, image.removePrefix("images/"), bytes)))
            db.content().insertScenes(listOf(
                WordSceneEntity(source, word, "scene", "Sense", image, "Scene $index", true, ContentParser.rawSha256(bytes), 0),
            ))
        }
    }

    private fun question(vm: WordQuizViewModel): WordQuizUi.Question = runBlocking {
        withTimeout(10_000) { while (vm.ui is WordQuizUi.Loading) delay(10) }
        vm.ui as WordQuizUi.Question
    }

    private fun advance(vm: WordQuizViewModel, guessed: Boolean = false) = runBlocking {
        vm.next(guessed)
        withTimeout(10_000) { db.withTransaction {} }
    }

    private fun gate(owner: String = stack, modes: List<String> = listOf("WordJpEn", "WordEnJp")) = runBlocking {
        for (word in db.content().words(source)) for (mode in modes) {
            db.reviews().putState(ReviewStateEntity(source, owner, word.word, mode, "Review", 3.0, 5.0, 999, 500, 2, 0))
        }
    }

    @Test
    fun readingHelpDoesNotWriteOrChangeGradingAndInvalidatesOldVoiceAndPopupCallbacks() = runBlocking {
        install()
        val vm = WordQuizViewModel(services)
        vm.ensureStarted(WordDirection.JpToEn, false)
        val q = question(vm)
        val budget = db.newAllowance(services.settings, source, Instant.now()).remaining
        val voice = vm.voiceGeneration
        vm.openReadingTip(q)
        val first = vm.readingTip!!
        vm.openReadingTip(q)
        val second = vm.readingTip!!
        assertNotSame(first, second)
        vm.closeReadingTip(first)
        assertSame(second, vm.readingTip)
        vm.pick(q.word.word, origin = q)
        assertSame(q, vm.ui)
        vm.ensureStarted(WordDirection.JpToEn, false)
        assertSame(second, vm.readingTip)
        vm.closeReadingTip(second)
        vm.pick(q.word.word, "old speech", q, voice)
        assertSame(q, vm.ui)
        db.withTransaction {}
        assertTrue(db.reviews().allLog().isEmpty())
        assertTrue(db.reviews().allStates().isEmpty())
        assertEquals(budget, db.newAllowance(services.settings, source, Instant.now()).remaining)
        vm.pick(q.word.word, origin = q)
        advance(vm)
        assertEquals(3, db.reviews().allLog().single().grade)
        assertEquals(setOf("WordJpEn"), db.reviews().allStates().map { it.mode }.toSet())
        vm.openReadingTip(q)
        vm.pick(q.word.word, origin = q)
        assertNull(vm.readingTip)
        assertTrue(vm.ui is WordQuizUi.Question)
    }

    @Test
    fun voiceRetryKeepsCapturedMetadataAndRejectsOldQuestionAnswerAndDismissal() = runBlocking {
        install()
        val originalSource = source
        val vm = WordQuizViewModel(services)
        vm.ensureStarted(WordDirection.JpToEn, false)
        val q = question(vm)
        vm.openReadingTip(q)
        val oldTip = vm.readingTip!!
        vm.closeReadingTip(oldTip)
        db.content().insertWords(db.content().words(source).map { it.copy(reading = "changed", title = "Changed meaning") })
        db.content().insertKanji(db.content().kanji(source).map { it.copy(jlpt = 5) })
        services.settings.setWordStack(WordStacks.all.id)
        assertTrue(services.settings.setSource("https://github.com/other/content", "main"))
        vm.pick(q.word.word, "heard", q, vm.voiceGeneration)
        val answer = vm.ui as WordQuizUi.Answer
        vm.retry(answer)
        val retry = question(vm)
        assertNotSame(q, retry)
        assertSame(q.word, retry.word)
        assertSame(q.options, retry.options)
        assertSame(q.kanjiHelp, retry.kanjiHelp)
        assertEquals(q.font, retry.font)
        vm.openReadingTip(retry)
        val tip = vm.readingTip!!
        assertEquals(q.word.reading, tip.help.reading)
        vm.closeReadingTip(oldTip)
        assertSame(tip, vm.readingTip)
        vm.closeReadingTip(tip)
        vm.pick(q.word.word, origin = q)
        vm.retry(answer)
        vm.next(origin = answer)
        assertSame(retry, vm.ui)
        vm.pick(retry.word.word, origin = retry)
        advance(vm, guessed = true)
        assertEquals(originalSource, db.reviews().allLog().single().sourceId)
        assertEquals(stack, db.reviews().allLog().single().stack)
        assertEquals(2, db.reviews().allLog().single().grade)
        services.settings.setSource("https://github.com/stop-cran/learning-japanese", "main")
        services.settings.setWordStack(stack)
        val fresh = WordQuizViewModel(services)
        fresh.ensureStarted(WordDirection.JpToEn, false)
        assertTrue(question(fresh).kanjiHelp.values.all { it.ranges.all { range -> range.level == null } })
        assertEquals("changed", question(fresh).word.reading)
    }

    @Test
    fun laterQuestionsAndReasksKeepTheirWholeRunSnapshot() = runBlocking {
        install()
        val vm = WordQuizViewModel(services)
        vm.ensureStarted(WordDirection.JpToEn, false)
        val first = question(vm)
        vm.pick(NO_ANSWER)
        advance(vm)
        db.content().insertWords(db.content().words(source).map { it.copy(reading = "changed") })
        db.content().insertKanji(db.content().kanji(source).map { it.copy(jlpt = 5) })
        repeat(3) {
            val next = question(vm)
            assertTrue(next.kanjiHelp.isNotEmpty())
            assertEquals(next.word.reading, next.kanjiHelp.getValue(next.word.word).reading)
            assertTrue(next.word.reading != "changed")
            vm.pick(next.word.word)
            advance(vm)
        }
        val repeat = question(vm)
        assertEquals(first.word, repeat.word)
        assertEquals(first.kanjiHelp, repeat.kanjiHelp)
        assertEquals(1, db.reviews().allLog().first().grade)
    }

    @Test
    fun everyJapaneseOptionIncludingPaddingGetsUniformHelpButNoPopup() = runBlocking {
        install()
        gate(owner = WordStacks.n4.id)
        val vm = WordQuizViewModel(services)
        vm.ensureStarted(WordDirection.EnToJp, true)
        val q = question(vm)
        assertEquals(q.options.map { it.word }.toSet(), q.kanjiHelp.keys)
        assertTrue(q.options.all { it.label == "${it.word} (${q.kanjiHelp.getValue(it.word).reading})" })
        vm.openReadingTip(q)
        assertNull(vm.readingTip)
        vm.pick(q.word.word)
        advance(vm)
        assertEquals(WordStacks.n4.id, db.reviews().allLog().single().stack)
        assertEquals("WordEnJp", db.reviews().allLog().single().mode)
    }

    @Test
    fun pictureDirectionsUsePromptOnlyOrOptionOnlyHelpAndKeepScenesOnRetry() {
        install()
        gate()
        for (direction in listOf(WordDirection.WordToPicture, WordDirection.Picture)) {
            val vm = WordQuizViewModel(services)
            vm.ensureStarted(direction, false)
            val q = question(vm)
            if (direction == WordDirection.WordToPicture) {
                assertEquals(setOf(q.word.word), q.kanjiHelp.keys)
                vm.openReadingTip(q)
                assertEquals(q.word.reading, vm.readingTip!!.help.reading)
                vm.closeReadingTip(vm.readingTip!!)
            } else {
                assertEquals(q.options.map { it.word }.toSet(), q.kanjiHelp.keys)
                vm.openReadingTip(q)
                assertNull(vm.readingTip)
            }
            vm.pick(q.word.word, "heard")
            vm.retry()
            val retry = question(vm)
            assertSame(q.picture, retry.picture)
            assertSame(q.kanjiHelp, retry.kanjiHelp)
        }
    }

    @Test
    fun readingExtraSkipsLearnedKanaIncludingPaddingWithoutDeletingItsHistory() = runBlocking {
        install()
        val kanji = db.content().words(source).map { it.word }.toSet()
        val kana = setOf("ネコ", "ピカピカ")
        db.content().insertWords(kana.map { word ->
            WordEntity(source, word, if (word == "ネコ") "ねこ" else "ぴかぴか", "Meaning $word", "wago", "", "", "Body", 5)
        })
        gate(owner = WordStacks.n4.id, modes = listOf("WordReading"))
        db.reviews().record(
            ReviewStateEntity(source, stack, "ネコ", "WordReading", "Review", 3.0, 5.0, 999, 500, 2, 0),
            ReviewLogEntity(0, source, stack, "ネコ", "WordReading", 3, 500),
        )
        val oldStates = db.reviews().allStates().filter { it.kanji in kana }
        val oldLog = db.reviews().allLog().filter { it.kanji in kana }
        val vm = WordQuizViewModel(services)
        vm.ensureStarted(WordDirection.Reading, true)
        val seen = mutableSetOf<String>()
        repeat(kanji.size) {
            val q = question(vm)
            assertTrue(q.word.word in kanji)
            seen += q.word.word
            vm.pick(q.word.reading, origin = q)
            advance(vm)
        }
        assertEquals(kanji, seen)
        assertTrue(vm.ui is WordQuizUi.Done)
        assertEquals(oldStates, db.reviews().allStates().filter { it.kanji in kana })
        assertEquals(oldLog, db.reviews().allLog().filter { it.kanji in kana })
        assertTrue(db.reviews().allLog().filter { it.kanji in kanji }.all { it.stack == WordStacks.n4.id })
    }

    @Test
    fun readingTypingAllAndThematicDecksDoNotOfferAnswerHelp() {
        install()
        gate()
        for (direction in listOf(WordDirection.Reading, WordDirection.Type)) {
            val vm = WordQuizViewModel(services)
            vm.ensureStarted(direction, false)
            if (direction == WordDirection.Reading) {
                val q = question(vm)
                assertTrue(q.kanjiHelp.isEmpty())
                vm.openReadingTip(q)
            } else {
                runBlocking { withTimeout(10_000) { while (vm.ui is WordQuizUi.Loading) delay(10) } }
                assertTrue(vm.ui is WordQuizUi.TypeQuestion)
                vm.onDraft("draft")
                assertEquals("draft", vm.draft)
            }
            assertNull(vm.readingTip)
        }
        for (deck in listOf(WordStacks.all, WordStacks.onomatopoeia)) {
            services.settings.setWordStack(deck.id)
            val vm = WordQuizViewModel(services)
            vm.ensureStarted(WordDirection.JpToEn, false)
            val q = question(vm)
            assertTrue(q.kanjiHelp.isEmpty())
            vm.openReadingTip(q)
            assertNull(vm.readingTip)
        }
    }
}
