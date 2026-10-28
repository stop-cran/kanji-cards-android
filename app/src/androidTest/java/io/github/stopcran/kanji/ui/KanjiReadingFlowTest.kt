package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.core.reading.ReadingKind
import io.github.stopcran.kanji.core.srs.Stacks
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.KanjiEntity
import io.github.stopcran.kanji.data.ReviewStateEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.joinSep
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class KanjiReadingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun successfulOnYomiKeepsKindAndRecordsNext() = exercise(ReadingKind.On, Response.Correct)
    @Test fun successfulKunYomiKeepsKindAndRecordsGuess() = exercise(ReadingKind.Kun, Response.Correct, guessed = true)
    @Test fun wrongOnYomiKeepsKindAndReference() = exercise(ReadingKind.On, Response.Wrong)
    @Test fun unknownKunYomiKeepsKindAndReference() = exercise(ReadingKind.Kun, Response.Unknown)

    private enum class Response { Correct, Wrong, Unknown }

    private fun exercise(kind: ReadingKind, response: Response, guessed: Boolean = false) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = "reading-feedback-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val settings = Settings(isolated).also {
            it.setSource("https://github.com/fixture/readings", "main")
            it.setStack(Stacks.ALL)
            it.setWeekPlan("FFFFFFF")
            it.setDailyNewCards(0)
        }
        val models = ViewModelStore()
        val route = "kreading:${UUID.randomUUID()}"
        val vm = KanjiReadingViewModel(Services(db, settings, context)).also { models.put(route, it) }
        val document = mutableStateOf<String?>(null)
        try {
            runBlocking {
                val source = settings.source.value.id
                val cards = listOf(
                    Triple("日", listOf("ニチ", "ジツ").joinSep(), listOf("ひ", "か").joinSep()),
                    Triple("木", "モク", "き"), Triple("山", "サン", "やま"), Triple("川", "セン", "かわ"),
                )
                db.content().insertKanji(cards.mapIndexed { index, (kanji, on, kun) ->
                    KanjiEntity(source, kanji, "Fixture $kanji", 5, "", null, null, null, on, kun, "",
                        "Fixture reading reference.\n\nReference: [Related reading note](../articles/reading-note.md)", null, index)
                })
                val now = System.currentTimeMillis()
                db.reviews().putState(ReviewStateEntity(
                    source, Stacks.ALL, "日", kind.mode.name, "Review", 10.0, 5.0, now - 60_000, now - 86_400_000, 2, 0,
                    reading = if (kind == ReadingKind.On) "にち" else "ひ",
                ))
            }
            rule.setContent { MaterialTheme {
                if (document.value != null) Page("Linked reading reference", onBack = { document.value = null }) {
                    Text("Fixture linked article")
                } else KanjiReadingScreen(route, {}, { document.value = it }, {}, vm)
            } }
            rule.waitUntil(10_000) { vm.ui is ReadingUi.Question }
            val question = rule.runOnIdle { vm.ui as ReadingUi.Question }
            assertEquals(kind, question.question.kind)
            rule.onNodeWithText("Pick the ${kind.label}").performScrollTo().assertIsDisplayed()
            val right = question.question.options.single { it.key == question.question.correctKey }
            val pick = when (response) {
                Response.Correct -> right.label
                Response.Wrong -> question.question.options.first { it.key != right.key }.label
                Response.Unknown -> "I don't know"
            }
            rule.onNodeWithText(pick).performScrollTo().performClick()
            val headline = when (response) {
                Response.Correct -> "Correct! — ${kind.label}"
                Response.Wrong -> "Not quite — a ${kind.label}"
                Response.Unknown -> "No problem — a ${kind.label}"
            }
            rule.onNodeWithText(headline, substring = true).performScrollTo().assertIsDisplayed()
            rule.onNodeWithContentDescription("Correct answer: ${right.label}").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("Fixture 日: ニチ、ジツ、ひ、か").performScrollTo().assertIsDisplayed()
            if (response == Response.Correct) {
                File(context.getExternalFilesDir(null), "ux99-${kind.name}-feedback.png").outputStream().use {
                    rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } else {
                rule.onNodeWithText("I guessed").assertDoesNotExist()
                rule.onNodeWithText("Read the article").performScrollTo().performClick()
            }
            rule.onNodeWithText("Fixture reading reference.").performScrollTo().assertIsDisplayed()
            val link = rule.onNodeWithText("Reference: Related reading note").performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            link.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            link.performTouchInput { click(layouts.single().getBoundingBox(11).center) }
            rule.onNodeWithText("Linked reading reference").assertIsDisplayed()
            assertEquals("articles/reading-note.md", document.value)
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText(headline, substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(0, runBlocking { db.reviews().allLog().size })
            rule.onNodeWithText(if (guessed) "I guessed" else "Next").performScrollTo().performClick()
            rule.waitUntil(10_000) { runBlocking { db.reviews().allLog().size == 1 } }
            val log = runBlocking { db.reviews().allLog().single() }
            assertEquals(if (response != Response.Correct) 1 else if (guessed) 2 else 3, log.grade)
            assertEquals(kind.mode.name, log.mode)
            assertEquals(right.key, log.reading)
        } finally {
            rule.runOnIdle { models.clear() }
            runBlocking {
                withTimeout(10_000) {
                    while (db.reviews().allSessions().any { it.endedAtMs == null }) delay(10)
                }
            }
            db.close()
            context.deleteSharedPreferences(preferences)
        }
    }
}
