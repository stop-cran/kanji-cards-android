package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.ContentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KanjiQuizReadingsTest {
    private fun text(fields: String) = """
        ---
        kanji: 日
        title: sun, day
        onyomi: [ニチ, ジツ]
        kunyomi: [ひ, -び]
        $fields
        ---

        # 日
    """.trimIndent()

    @Test
    fun optionalQuizReadingsPreserveFullMetadataAndDistinguishAbsentFromEmpty() {
        val legacy = ContentParser.parseKanji("kanji/日.md", text(""))
        assertNull(legacy.quizOnyomi)
        assertNull(legacy.quizKunyomi)
        val curated = ContentParser.parseKanji("kanji/日.md", text("quizOnyomi: ['ジツ', ニチ]")
            .replace("quizOnyomi:", "quizKunyomi: []\nquizOnyomi:"))
        assertEquals(listOf("ジツ", "ニチ"), curated.quizOnyomi)
        assertEquals(emptyList<String>(), curated.quizKunyomi)
        assertEquals(legacy.onyomi, curated.onyomi)
        assertEquals(legacy.kunyomi, curated.kunyomi)
        assertEquals(legacy.body, curated.body)
    }

    @Test
    fun malformedQuizReadingDeclarationsRejectOnlyTheirCardRatherThanFallingBack() {
        val declarations = listOf(
            "quizOnyomi:", "quizOnyomi: null", "quizOnyomi: ニチ", "quizOnyomi: [ニチ, ニチ]",
            "quizOnyomi: [にち]", "quizKunyomi: [か]", "quizOnyomi: ['']", "quizOnyomi: [true]",
            "quizOnyomi: [[ニチ]]", "quizOnyomi: [ニチ,]", "quizOnyomi: [] # hidden",
            "'quizOnyomi': []", "\"quizKunyomi\": []", "  quizOnyomi: []",
            "quizOnyomi: [ニチ]\nquizOnyomi: []", "quizOnyomi: []\n'quizOnyomi': [ニチ]",
        )
        for (field in declarations) {
            val md = text("").replace("kunyomi: [ひ, -び]", "kunyomi: [ひ, -び]\n$field")
            assertThrows(field, ContentException::class.java) { ContentParser.parseKanji("kanji/日.md", md) }
            val files = mapOf("kanji/日.md" to md.toByteArray(), "articles/example.md" to "# Example".toByteArray())
            val hashes = files.entries.joinToString(",") { (path, bytes) -> "\"$path\":\"${ContentParser.sha256(bytes)}\"" }
            val manifest = """{"schemaVersion":1,"contentVersion":"v1","files":{$hashes}}""".toByteArray()
            val parsed = ContentParser.parse(files + ("manifest.json" to manifest))
            assertTrue(field, parsed.kanji.isEmpty())
            assertTrue(parsed.problems.toString(), parsed.problems.any { it.startsWith("kanji/日.md:") })
            assertEquals(1, parsed.articles.size)
        }
    }
}
