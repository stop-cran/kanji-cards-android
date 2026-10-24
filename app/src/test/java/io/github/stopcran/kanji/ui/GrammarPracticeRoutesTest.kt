package io.github.stopcran.kanji.ui

import android.app.Application
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import io.github.stopcran.kanji.core.grammar.GrammarTargetKind
import io.github.stopcran.kanji.restoreStudyRoutes
import io.github.stopcran.kanji.stateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class GrammarPracticeRoutesTest {
    @Test
    fun rotationPreservesTheRouteWhileProcessDeathRequiresAFreshSameTargetPreview() {
        val target = GrammarPracticeTarget("owner/repo", "feature/grammar", GrammarTargetKind.Group, "legacy:set:contrast")
        val route = GrammarPracticeRoutes.create(target)
        val stack = listOf("home", "grammar-home", route, "source-doc:article")
        assertEquals(stack, restoreStudyRoutes(stack, sameProcess = true))
        val restored = restoreStudyRoutes(stack, sameProcess = false)
        assertEquals(stack.take(2), restored.take(2))
        assertEquals(stack.last(), restored.last())
        assertNotEquals(route, restored[2])
        assertTrue(restored[2].endsWith(":restart"))
        assertEquals(target, GrammarPracticeRoutes.target(restored[2]))
        assertEquals(listOf("home"), restoreStudyRoutes(listOf("home", "grammarquiz:123:extra"), sameProcess = false))
        val continuation = GrammarPracticeRoutes.create(target)
        assertNotEquals(route, continuation)
        assertEquals(target, GrammarPracticeRoutes.target(continuation))
        assertFalse(continuation.endsWith(":restart"))
        assertEquals(stateKey(2, route), stateKey(2, continuation))
        assertEquals(stateKey(2, route), stateKey(2, restored[2]))
        assertNotEquals(stateKey(2, route), stateKey(3, continuation))
        assertNotEquals(stateKey(2, route), stateKey(2, GrammarPracticeRoutes.create(target.copy(branch = "main"))))
        assertNotEquals(stateKey(2, route), stateKey(2, GrammarPracticeRoutes.create(target.copy(kind = GrammarTargetKind.Set))))
    }

    @Test
    fun legacyCatalogsAndDuplicateDetailsRecoverWithoutLosingAnArticleOrItsOriginalTarget() {
        val destination = GrammarMapDestination("owner/repo", "feature/grammar", chapter = "foundations")
        val target = requireNotNull(destination.target())
        val route = GrammarPracticeRoutes.create(target)
        val saved = listOf("home", "grammar-home", GrammarPracticeRoutes.PICKER,
            GrammarRoadmapRoutes.create(destination), route, "source-doc:original")
        for (sameProcess in listOf(true, false)) {
            val restored = restoreStudyRoutes(saved, sameProcess)
            assertEquals(listOf("home", "grammar-home"), restored.take(2))
            assertEquals(4, restored.size)
            assertEquals(target, GrammarPracticeRoutes.target(restored[2]))
            assertEquals("source-doc:original", restored.last())
            assertEquals(!sameProcess, restored[2].endsWith(":restart"))
        }
        assertEquals(listOf("home", "grammar-home"), restoreStudyRoutes(
            listOf("home", "grammar-home", GrammarRoadmapRoutes.create(destination.overview())), sameProcess = true,
        ))
        assertEquals(listOf("home", GrammarPracticeRoutes.UNAVAILABLE), restoreStudyRoutes(
            listOf("home", "grammar-map:%bad"), sameProcess = true,
        ))
    }

    @Test
    fun malformedTargetRoutesCannotFallBackToOrdinaryPractice() {
        for (route in listOf("grammarquiz:123:target", "grammarquiz:123:target:bad", "grammarquiz:123:target:bad:extra")) {
            assertTrue(GrammarPracticeRoutes.isTargeted(route))
            assertFalse(GrammarPracticeRoutes.isOrdinary(route))
            assertThrows(IllegalArgumentException::class.java) { GrammarPracticeRoutes.target(route) }
            assertEquals(GrammarPracticeRoutes.UNAVAILABLE, GrammarPracticeRoutes.restart(route))
        }
    }
}
