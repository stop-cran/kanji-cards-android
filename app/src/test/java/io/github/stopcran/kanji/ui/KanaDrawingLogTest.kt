package io.github.stopcran.kanji.ui

import android.app.Application
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import io.github.stopcran.kanji.core.kana.KANA_SOURCE_ID
import io.github.stopcran.kanji.core.kana.KanaMode
import io.github.stopcran.kanji.core.kana.KanaScript
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.DrawingLog
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.SavedDrawing
import io.github.stopcran.kanji.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KanaDrawingLogTest {
    private lateinit var services: Services
    private lateinit var files: File
    private val store = ViewModelStore()
    private val recognizer = object : InkRecognition {
        override suspend fun prepare() = ModelState.Unavailable
        override suspend fun candidates(strokes: List<List<TimedPt>>): List<String>? = null
        override fun close() = Unit
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("settings", 0).edit().clear().commit()
        files = File(application.cacheDir, "kana-log-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(application) {
            override fun getFilesDir() = files
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        services = Services(db, Settings(context).also {
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(0)
            it.setSaveDrawings(true)
        }, context)
    }

    @After fun cleanup() {
        store.clear()
        services.db.close()
        files.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test fun checkedKanaStoresRawTimedPointsAndExportsTheSameJson() {
        val vm = start()
        val question = vm.ui as KanaStudyUi.DrawQuestion
        assertFalse(question.trace)
        val strokes = vm.reference(question.card).mapIndexed { index, s ->
            s.mapIndexed { point, p -> TimedPt((p.x * 3).toFloat(), (p.y * 3).toFloat(), 1_000L + index * 100 + point) }
        }
        vm.checkDrawing(strokes, 327f)
        await { vm.ui is KanaStudyUi.DrawAnswer }
        val answer = vm.ui as KanaStudyUi.DrawAnswer
        val saved = DrawingLog.dir(services.context).listFiles()!!.single()
        val record = Json.decodeFromString<SavedDrawing>(saved.readText())
        assertEquals("きゃ", record.kanji)
        assertEquals(327f, record.canvasPx)
        assertEquals(answer.outcome.name, record.outcome)
        assertEquals(null, record.candidates)
        assertEquals(strokes.map { s -> s.map { listOf(it.x, it.y, it.t.toFloat()) } }, record.strokes)
        val output = ByteArrayOutputStream()
        assertEquals(1, DrawingLog.exportZip(services.context, output))
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals(saved.name, zip.nextEntry.name)
            assertEquals(saved.readText(), zip.readBytes().toString(Charsets.UTF_8))
            assertEquals(null, zip.nextEntry)
        }
        assertTrue(runBlocking { services.db.reviews().allLog().isEmpty() })
    }

    @Test fun optOutAndEmptyGiveUpDoNotWriteDrawings() {
        services.settings.setSaveDrawings(false)
        val vm = start()
        vm.checkDrawing(listOf(listOf(TimedPt(10f, 20f, 30L), TimedPt(30f, 40f, 50L))), 109f)
        await { vm.ui is KanaStudyUi.DrawAnswer }
        assertEquals(0, DrawingLog.count(services.context))
        store.clear()
        services.settings.setSaveDrawings(true)
        val giveUp = start()
        giveUp.checkDrawing(emptyList(), 109f)
        await { giveUp.ui is KanaStudyUi.DrawAnswer }
        assertEquals(0, DrawingLog.count(services.context))
    }

    @Test fun firstKanaTraceRemainsUnloggedAndDoesNotRecordAReview() {
        services.settings.setDailyNewCards(1)
        val vm = start(reviewed = false)
        val question = vm.ui as KanaStudyUi.DrawQuestion
        assertTrue(question.trace)
        val strokes = vm.reference(question.card).map { s -> s.map { TimedPt(it.x.toFloat(), it.y.toFloat(), 100L) } }
        vm.checkDrawing(strokes, 109f)
        await { vm.ui is KanaStudyUi.DrawAnswer }
        assertEquals(0, DrawingLog.count(services.context))
        vm.next()
        assertTrue(runBlocking { services.db.reviews().allLog().isEmpty() })
    }

    @Test fun bundledHandakutenReferencesAcceptClockwiseAtEveryStartInBothScripts() {
        val data = services.context.assets.open("kana-strokes.json").bufferedReader().use {
            Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, StrokeData>>(it.readText())
        }
        val matcher = StrokeMatcher()
        for (kana in "ぱぴぷぺぽパピプペポ") {
            val reference = data.getValue(kana.toString()).strokes.map { s -> s.points.map { Pt(it[0], it[1]) } }
            val circle = reference.last()
            val centerX = (circle.minOf { it.x } + circle.maxOf { it.x }) / 2
            val centerY = (circle.minOf { it.y } + circle.maxOf { it.y }) / 2
            val radiusX = (circle.maxOf { it.x } - circle.minOf { it.x }) / 2
            val radiusY = (circle.maxOf { it.y } - circle.minOf { it.y }) / 2
            for (phase in 0 until 24) {
                val drawn = (0..48).map { i ->
                    val angle = phase * PI / 12 + i * PI / 24
                    Pt(centerX + radiusX * cos(angle), centerY + radiusY * sin(angle))
                }
                val clockwise = matcher.match(reference, reference.dropLast(1) + listOf(drawn))
                assertTrue("$kana clockwise $phase: ${clockwise.issues}", clockwise.clean)
                val reverse = matcher.match(reference, reference.dropLast(1) + listOf(drawn.reversed()))
                assertTrue("$kana counterclockwise $phase: ${reverse.issues}", reverse.issues.any { it.type == IssueType.Reversed })
            }
        }
    }

    private fun start(reviewed: Boolean = true): KanaStudyViewModel {
        if (reviewed) runBlocking {
            val now = Instant.now().toEpochMilli()
            services.db.reviews().putState(ReviewStateEntity(
                KANA_SOURCE_ID, KanaScript.Hiragana.id, "きゃ", KanaMode.Draw.name,
                "Review", 10.0, 5.0, now - 60_000, now - 86_400_000, 3, 0,
            ))
        }
        val vm = KanaStudyViewModel(services, recognizer)
        store.put(UUID.randomUUID().toString(), vm)
        vm.ensureStarted(KanaScript.Hiragana, KanaMode.Draw, extra = false)
        await { vm.ui !is KanaStudyUi.Loading }
        assertTrue("Expected drawing question, got ${vm.ui}", vm.ui is KanaStudyUi.DrawQuestion)
        return vm
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for Kana state", predicate())
    }
}
