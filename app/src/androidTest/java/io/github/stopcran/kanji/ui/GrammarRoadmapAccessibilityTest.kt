package io.github.stopcran.kanji.ui

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import io.github.stopcran.kanji.core.grammar.GrammarMapProgress
import io.github.stopcran.kanji.core.grammar.GrammarRoadmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises Android's exported accessibility nodes without suspending the installed screen reader. */
class GrammarRoadmapAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun nativeAccessibilityCanPanFocusAndActivateCardsWithTalkBackEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("com.google.android.marvin.talkback") == true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val ids = listOf("foundation", "dependent") + (0 until 40).map { "parallel-$it" }
        val layout = GrammarRoadmap.layout(ids, listOf(GrammarLeafEdge("foundation", "dependent")))
        val destination = GrammarMapDestination("accessibility/local", "main")
        val nodes = ids.associateWith { id ->
            GrammarMapNode(destination.group(id), id,
                GrammarMapProgress(1, 4, 4, 0, 0, 1, 0, 0, if (id == "dependent") 0 else 1, if (id == "dependent") 1 else 0))
        }
        var activated: String? = null
        rule.setContent {
            KanjiTheme {
                Page("Roadmap accessibility fixture") {
                    GrammarRoadmapDiagram(layout, nodes) { activated = it.group }
                }
            }
        }
        rule.waitForIdle()
        automation.waitForIdle(250, 5000)
        val right = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        fun scroller(action: Int) = requireNotNull(findNode(automation.rootInActiveWindow) { node ->
            node.actionList.any { it.id == action }
        })
        val before = bounds(card(automation, "foundation"))
        assertTrue(scroller(right).performAction(right))
        rule.waitForIdle()
        automation.waitForIdle(250, 5000)
        val after = bounds(card(automation, "dependent"))
        assertTrue("Native horizontal accessibility action must bring the next column into view", after.left < before.right)
        assertTrue(scroller(down).performAction(down))
        rule.waitForIdle()
        automation.waitForIdle(250, 5000)
        val verticalPosition = rule.onNodeWithTag("grammar-diagram").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Native vertical action must move the diagram: $verticalPosition", verticalPosition > 0f)
        assertTrue(scroller(up).performAction(up))
        rule.waitForIdle()
        automation.waitForIdle(250, 5000)
        val target = card(automation, "dependent")
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        rule.waitForIdle()
        automation.waitForIdle(250, 5000)
        assertEquals(target, automation.rootInActiveWindow.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
        File(context.cacheDir, "roadmap-talkback-native.png").outputStream().use {
            requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        rule.runOnIdle { assertEquals("dependent", activated) }
    }

    private fun card(automation: UiAutomation, label: String): AccessibilityNodeInfo {
        val text = requireNotNull(findNode(automation.rootInActiveWindow) { it.text?.contains(label) == true }) {
            "Accessible label missing: $label"
        }
        return requireNotNull(generateSequence(text) { it.parent }.firstOrNull { it.isClickable }) {
            "Accessible card is not clickable: $label"
        }
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect {
        check(node.refresh()) { "Accessibility node disappeared before measuring it" }
        return Rect().also(node::getBoundsInScreen)
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        // Native node snapshots can retain pre-scroll actions and bounds after Compose has rendered the next frame.
        if (!node.refresh()) return null
        if (predicate(node)) return node
        return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let { findNode(it, predicate) } }
    }
}
