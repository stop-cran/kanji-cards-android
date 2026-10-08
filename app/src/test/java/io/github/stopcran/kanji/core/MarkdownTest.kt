package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.FrontMatter
import io.github.stopcran.kanji.core.markdown.Block
import io.github.stopcran.kanji.core.markdown.LinkTarget
import io.github.stopcran.kanji.core.markdown.Links
import io.github.stopcran.kanji.core.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MarkdownTest {
    @Test
    fun resolvesRelativeLinks() {
        assertEquals(LinkTarget.Doc("words/上下.md"), Links.resolve("kanji/上.md", "../words/上下.md"))
        assertEquals(LinkTarget.Doc("kanji/下.md"), Links.resolve("kanji/上.md", "下.md"))
        assertEquals(LinkTarget.Doc("articles/a.md"), Links.resolve("words/x.md", "../articles/a.md#sec"))
        assertEquals(LinkTarget.Web("https://example.org/a"), Links.resolve("kanji/上.md", "https://example.org/a"))
    }

    @Test
    fun rejectsUnsafeLinks() {
        assertNull(Links.resolve("kanji/上.md", "../../etc/passwd.md"))
        assertNull(Links.resolve("kanji/上.md", "javascript:alert(1)"))
        assertNull(Links.resolve("kanji/上.md", "http://example.org"))
        assertNull(Links.resolve("kanji/上.md", "../README.md"))
        assertNull(Links.resolve("kanji/上.md", "../tools/x.md"))
        assertNull(Links.resolve("kanji/上.md", "/words/a.md"))
    }

    @Test
    fun parsesInlineStyles() {
        val spans = Markdown.inline("a **bold** and *it* `c` [下](下.md) \\*x", "kanji/上.md")
        assertTrue(spans.any { it.text == "bold" && it.bold })
        assertTrue(spans.any { it.text == "it" && it.italic })
        assertTrue(spans.any { it.text == "c" && it.code })
        assertTrue(spans.any { it.text == "下" && it.link == LinkTarget.Doc("kanji/下.md") })
        assertTrue(spans.last().text.endsWith("*x"))
    }

    @Test
    fun parsesBlocks() {
        val md = """
            # Title

            Para line one
            line two

            | A | B |
            | --- | --- |
            | 1 | [x](https://e.org) |

            - one
            - two
              continued
            1. first

            > quoted
            > text

            ---
        """.trimIndent()
        val b = Markdown.parse(md, "articles/a.md")
        assertTrue(b[0] is Block.Heading)
        assertEquals("Para line one line two", (b[1] as Block.Paragraph).spans.single().text)
        val t = b[2] as Block.Table
        assertEquals(2, t.header.size)
        assertEquals(1, t.rows.size)
        assertEquals("two continued", (b[4] as Block.ListItem).spans.single().text)
        assertEquals(1, (b[5] as Block.ListItem).ordinal)
        assertEquals("quoted text", ((b[6] as Block.Quote).blocks.single() as Block.Paragraph).spans.single().text)
        assertTrue(b.last() is Block.Rule)
    }

    @Test
    fun rendersEveryRealDocumentWithoutRawMarkers() {
        val root = File("../../learning-japanese")
        assumeTrue(File(root, "manifest.json").exists())
        for (folder in listOf("kanji", "words", "articles")) {
            for (f in File(root, folder).listFiles { x -> x.extension == "md" }!!) {
                val body = FrontMatter.parse(f.readText())?.body ?: f.readText()
                val blocks = Markdown.parse(body, "$folder/${f.name}")
                assertTrue("${f.name} produced no blocks", blocks.isNotEmpty())
                val spans = blocks.flatMap {
                    when (it) {
                        is Block.Paragraph -> it.spans
                        is Block.Heading -> it.spans
                        is Block.ListItem -> it.spans
                        is Block.Table -> it.header.flatten() + it.rows.flatten().flatten()
                        else -> emptyList()
                    }
                }
                assertTrue("${f.name}: leftover ** marker", spans.none { !it.code && it.text.contains("**") })
                assertTrue("${f.name}: leftover table pipe row", blocks.none { it is Block.Paragraph && it.spans.firstOrNull()?.text?.startsWith("|") == true })
            }
        }
    }
}
