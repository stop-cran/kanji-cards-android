package io.github.stopcran.kanji.ui

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.Services
import io.github.stopcran.kanji.SourceDocScreen
import io.github.stopcran.kanji.sourceDocRoute
import io.github.stopcran.kanji.stateKey
import io.github.stopcran.kanji.core.content.GrammarConstruction
import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import io.github.stopcran.kanji.core.content.GrammarRelationship
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.data.AppDatabase
import io.github.stopcran.kanji.data.ArticleEntity
import io.github.stopcran.kanji.data.GrammarEntity
import io.github.stopcran.kanji.data.ImageEntity
import io.github.stopcran.kanji.data.Settings
import io.github.stopcran.kanji.data.WordEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.abs

class GrammarReferenceFlowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun productionRouteKeepsTopicSourceScrollAndFeedbackAcrossNestedBack() = fixture { fixture, visible ->
        val routes = mutableStateListOf("grammarquiz:${UUID.randomUUID()}")
        val vm = GrammarQuizViewModel(Services(fixture.db, fixture.settings, fixture.context))
        val models = ViewModelStore().also { it.put(routes.first(), vm) }
        rule.setContent {
            if (visible.value) MaterialTheme {
                val holder = rememberSaveableStateHolder()
                val route = routes.last()
                fun pop() {
                    holder.removeState(stateKey(routes.lastIndex, route))
                    routes.removeAt(routes.lastIndex)
                }
                holder.SaveableStateProvider(stateKey(routes.lastIndex, route)) {
                    if (route.startsWith("source-doc:")) {
                        SourceDocScreen(fixture.app, route, { pop() }, { routes += it })
                    } else GrammarQuizScreen(route, {}, { source, path, topic ->
                        routes += sourceDocRoute(source, path, topic)
                    }, {}, {}, {}, vm = vm)
                }
            }
        }
        try {
            rule.waitUntil(10_000) { vm.ui is GrammarUi.Choose }
            val first = vm.ui as GrammarUi.Choose
            rule.onNodeWithText(first.options.first { it.id == first.exercise.answer }.label).performScrollTo().performClick()
            val answer = vm.ui as GrammarUi.Answer
            val firstTopic = vm.articleReference(answer.exercise.id).topic
            openReference(firstTopic)
            assertNotEquals(fixture.source, fixture.app.settings.source.value.id)
            awaitText("Reference for: $firstTopic")
            rule.onNodeWithText("Article contents").performClick()
            rule.onNodeWithText("3. Repeated heading").performClick()
            awaitText("Second section start.")
            val position = headingOffset("Repeated heading")
            assertTrue(position in 0f..35f)
            rule.onNodeWithText("Open nested").performClick()
            awaitText("Nested source preserved.")
            rule.onNodeWithText("Article contents").assertDoesNotExist()
            rule.onNodeWithText("Reference for:", substring = true).assertDoesNotExist()
            rule.onNodeWithContentDescription("Back").performClick()
            awaitText("Second section start.")
            assertTrue(abs(position - headingOffset("Repeated heading")) < 1f)

            rule.onNodeWithText("Open nested").performClick()
            awaitText("Nested source preserved.")
            runBlocking { fixture.replaceArticle("# Replacement heading\n\nUpdated article remains usable.") }
            rule.onNodeWithContentDescription("Back").performClick()
            awaitText("Updated article remains usable.")
            rule.onNodeWithText("Article contents").performClick()
            rule.onNodeWithText("1. Replacement heading").assertIsDisplayed()
            rule.onNodeWithText("3. Repeated heading").assertDoesNotExist()
            rule.onNodeWithText("Close contents").performClick()
            rule.onNodeWithContentDescription("Back").performClick()
            assertSame(answer, vm.ui)
            assertEquals(0, fixture.recorded())
            rule.onNodeWithText("Next").performScrollTo().performClick()
            rule.waitUntil(10_000) { vm.ui is GrammarUi.Type && fixture.recorded() == 1 }
            rule.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("kept response")
            rule.onNodeWithText("Check").performScrollTo().performClick()
            val typed = vm.ui as GrammarUi.Answer
            val secondTopic = vm.articleReference(typed.exercise.id).topic
            assertNotEquals(firstTopic, secondTopic)
            openReference(secondTopic)
            awaitText("Reference for: $secondTopic")
            rule.onNodeWithText("Updated article remains usable.").assertIsDisplayed()
            rule.onNodeWithContentDescription("Back").performClick()
            assertSame(typed, vm.ui)
            assertEquals("kept response", vm.draft)
            rule.onNodeWithText("Your answer: kept response").performScrollTo().assertIsDisplayed()
            assertEquals(1, fixture.recorded())
        } finally {
            rule.runOnIdle { visible.value = false; models.clear() }
            runBlocking { withTimeout(10_000) { while (fixture.db.reviews().allSessions().any { it.endedAtMs == null }) delay(10) } }
        }
    }

    @Test fun realWordGalleryContextAndLongHeadingUseTheCorrectLazyIndexAtLargeText() = fixture { fixture, visible ->
        val longHeading = "A deliberately long heading that wraps across several lines while remaining independently selectable"
        val wordBody = "# Overview\n\n" + filler("before", 20) + "\n## $longHeading\n\nWord destination.\n\n" + filler("after", 25)
        runBlocking {
            fixture.app.db.content().insertWords(listOf(WordEntity(
                fixture.source, "語", "ご", "Gallery reference", "wago", "語", "", wordBody,
                image = "images/reference.webp", imageScene = "Reference gallery",
            )))
            fixture.app.db.content().insertImages(listOf(ImageEntity(fixture.source, "reference.webp", image())))
        }
        rule.setContent {
            if (visible.value) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    MaterialTheme {
                        SourceDocScreen(fixture.app, sourceDocRoute(fixture.source, "words/語.md", "Topic: gallery / 語"), {}, {})
                    }
                }
            }
        }
        awaitText("Reference for: Topic: gallery / 語")
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Reference gallery").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("Reference gallery").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Article contents").performScrollTo().performClick()
        rule.onNodeWithText("2. $longHeading").performScrollTo().performClick()
        awaitText("Word destination.")
        assertTrue(headingOffset(longHeading) in 0f..35f)
    }

    private fun openReference(topic: String) {
        rule.onNodeWithText("Read about: $topic").performScrollTo().performClick()
    }

    private fun awaitText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun headingOffset(text: String): Float {
        val top = rule.onNodeWithTag("article-body").fetchSemanticsNode().boundsInRoot.top
        return (rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top - top) /
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    }

    private fun fixture(exercise: (Fixture, androidx.compose.runtime.MutableState<Boolean>) -> Unit) {
        Fixture().use { fixture ->
            val visible = mutableStateOf(true)
            try { exercise(fixture, visible) } finally {
                rule.runOnIdle { visible.value = false }
                rule.waitForIdle()
            }
        }
    }

    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as KanjiApp
        val source = "fixture/reference-${UUID.randomUUID()}"
        private val preferences = "reference-${UUID.randomUUID()}"
        private val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(preferences, mode)
        }
        val settings = Settings(isolated).also {
            it.setSource("https://github.com/$source", "main")
            it.setDailyNewCards(2)
            it.setWeekPlan("FFFFFFF")
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        init {
            val constructions = listOf(GrammarConstruction("a", "Alpha"), GrammarConstruction("b", "Beta"))
            val relationships = listOf("alpha", "beta").map { GrammarRelationship(it, "Topic: $it / reference", listOf("a", "b")) }
            val exercises = relationships.mapIndexed { index, relationship ->
                val target = constructions[index].id
                GrammarExercise(
                    "exercise-$index", target, relationship.id, "Complete ____.", "Synthetic reference fixture.",
                    "Evidence.", "Explanation.", target, listOf("model", "kept response"),
                    listOf(GrammarOption(target, "model", "Fits."), GrammarOption(constructions[1 - index].id, "other", "Does not fit.")),
                )
            }
            val set = GrammarSet(1, "reference", "Reference fixture", "articles/shared.md", constructions, relationships, exercises)
            runBlocking {
                db.content().insertGrammar(listOf(GrammarEntity(source, set.id, Json.encodeToString(GrammarSet.serializer(), set), 0)))
                replaceArticle("# Overview\n\nOpening.\n\n## Repeated heading\n\nFirst section start.\n\n" + filler("first", 25) +
                    "\n## Repeated heading\n\nSecond section start.\n\n[Open nested](nested.md)\n\n" + filler("second", 25))
                app.db.content().insertArticles(listOf(ArticleEntity(source, "nested", "Nested reference", "Nested source preserved.")))
            }
        }

        suspend fun replaceArticle(body: String) {
            app.db.content().insertArticles(listOf(ArticleEntity(source, "shared", "Shared reference", body)))
        }

        fun recorded() = runBlocking { db.reviews().allLog().size }

        override fun close() {
            db.close()
            context.deleteSharedPreferences(preferences)
            // Only unique fixture-source content is inserted into the real document renderer's cache; learner settings/history stay untouched.
            runBlocking {
                app.db.content().clearArticles(source)
                app.db.content().clearWords(source)
                app.db.content().clearImages(source)
            }
        }
    }

    companion object {
        private fun filler(prefix: String, count: Int) = (0 until count).joinToString("\n\n") { "$prefix paragraph $it with ordinary reference text." } + "\n"

        @Suppress("DEPRECATION")
        private fun image(): ByteArray {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 100, out)
                bitmap.recycle()
                out.toByteArray()
            }
        }
    }
}
