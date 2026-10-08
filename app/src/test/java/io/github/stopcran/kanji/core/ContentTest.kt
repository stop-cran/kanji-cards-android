package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.FrontMatter
import io.github.stopcran.kanji.core.content.SafeZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentTest {
    private val kanjiMd = """
        ---
        kanji: 日
        title: sun, day
        jlpt: 5
        tags: [jlpt-n5, grade-1]
        strokes: 4
        onyomi: [ニチ, ジツ]
        kunyomi: [ひ, -び]
        distractors: [月]
        ---

        # 日
    """.trimIndent().replace("\n", "\r\n")

    private val strokesJson =
        """{"schemaVersion":1,"kanji":"日","viewBox":[0,0,109,109],"strokeCount":1,"strokes":[{"id":1,"type":"a","points":[[1,2],[3,4]]}]}"""

    private fun manifest(files: Map<String, ByteArray>, schema: Int = 1, tamper: String? = null): ByteArray {
        val entries = files.entries.joinToString(",") { (p, b) ->
            val hash = if (p == tamper) "0".repeat(64) else ContentParser.sha256(b)
            "\"$p\":\"$hash\""
        }
        return """{"schemaVersion":$schema,"contentVersion":"v1","files":{$entries}}""".toByteArray()
    }

    private fun sample(): Map<String, ByteArray> = mapOf(
        "kanji/日.md" to kanjiMd.toByteArray(),
        "strokes/日.json" to strokesJson.toByteArray(),
        "articles/x.md" to "# Title X\ntext".toByteArray(),
    )

    @Test
    fun frontMatterParsesScalarsAndLists() {
        val p = FrontMatter.parse(kanjiMd)!!
        assertEquals("日", p.fields["kanji"])
        assertEquals(listOf("ニチ", "ジツ"), p.fields["onyomi"])
        assertEquals(listOf("ひ", "-び"), p.fields["kunyomi"])
        assertTrue(p.body.startsWith("# 日"))
        assertNull(FrontMatter.parse("no front matter"))
    }

    @Test
    fun parsesValidSnapshot() {
        val files = sample()
        val parsed = ContentParser.parse(files + ("manifest.json" to manifest(files)))
        assertEquals(emptyList<String>(), parsed.problems)
        assertEquals("sun, day", parsed.kanji.single().title)
        assertEquals(5, parsed.kanji.single().jlpt)
        assertEquals("Title X", parsed.articles.single().title)
        assertNotNull(parsed.strokes["日"])
    }

    @Test
    fun hashMismatchIsReportedAndFileSkipped() {
        val files = sample()
        val parsed = ContentParser.parse(files + ("manifest.json" to manifest(files, tamper = "kanji/日.md")))
        assertTrue(parsed.kanji.isEmpty())
        assertTrue(parsed.problems.any { it.contains("hash mismatch") })
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        val files = sample()
        try {
            ContentParser.parse(files + ("manifest.json" to manifest(files, schema = 2)))
            fail()
        } catch (_: ContentException) {
        }
    }

    @Test
    fun filenameMustMatchKanji() {
        val files = mapOf("kanji/月.md" to kanjiMd.toByteArray())
        val parsed = ContentParser.parse(files + ("manifest.json" to manifest(files)))
        assertTrue(parsed.kanji.isEmpty())
        assertEquals(1, parsed.problems.size)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, c) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun zipStripsRootAndFiltersFolders() {
        val bytes = zip("repo-main/manifest.json" to "{}", "repo-main/kanji/日.md" to "x", "repo-main/.github/a.yml" to "y", "repo-main/kanji/sub/a.md" to "z")
        val files = SafeZip.read(ByteArrayInputStream(bytes))
        assertEquals(setOf("manifest.json", "kanji/日.md"), files.keys)
    }

    @Test
    fun zipRejectsTraversalAndOversize() {
        try {
            SafeZip.read(ByteArrayInputStream(zip("repo/../evil.md" to "x")))
            fail()
        } catch (_: ContentException) {
        }
        try {
            SafeZip.read(ByteArrayInputStream(zip("repo/kanji/a.md" to "x".repeat(100))), SafeZip.Limits(maxFileBytes = 10))
            fail()
        } catch (_: ContentException) {
        }
    }

    @Test
    fun parsesRealContentRepoWhenPresent() {
        val root = File("../../learning-japanese")
        assumeTrue(File(root, "manifest.json").exists())
        val files = root.walkTopDown().filter { it.isFile && !it.path.contains(".git") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
        val parsed = ContentParser.parse(files)
        assertEquals(emptyList<String>(), parsed.problems)
        assertTrue(parsed.kanji.size >= 20)
        assertTrue(parsed.words.size >= 20)
        assertEquals(parsed.kanji.size, parsed.strokes.size)
    }
}
