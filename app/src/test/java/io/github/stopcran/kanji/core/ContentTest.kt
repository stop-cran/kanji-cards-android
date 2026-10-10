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

    private fun wordMd(jlptField: String = "") = """
        ---
        word: ねこ
        reading: ねこ
        title: cat
        kanji: []
        $jlptField
        ---

        # ねこ
    """.trimIndent()

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
    fun parsesExplicitWordLevelsIncludingKanaOnlyWords() {
        for (level in 1..5) {
            val word = ContentParser.parseWord("words/ねこ.md", wordMd("jlpt: $level"))
            assertEquals(level, word.jlpt)
            assertEquals("ねこ", word.word)
            assertTrue(word.kanji.isEmpty())
        }
    }

    @Test
    fun onlyOmittedWordLevelsRemainUnspecified() {
        assertNull(ContentParser.parseWord("words/ねこ.md", wordMd()).jlpt)
    }

    @Test
    fun invalidWordLevelsAreReportedAndOnlyTheBadFileIsSkipped() {
        for (value in listOf("", "   ", "''", "\"\"", "' '", "'5'", "\"5\"", "0", "6", "-1", "2147483648", "5.0", "true", "null", "~", "N5", "[5]", "[]", "{level: 5}")) {
            assertEquals(
                value,
                "words/いぬ.md: 'jlpt' must be a single top-level unquoted integer from 1 to 5",
                invalidWordProblem("jlpt: $value"),
            )
        }
    }

    @Test
    fun wordLevelCannotBeShadowedByDuplicateOrMultilineFields() {
        for (header in listOf(
            "jlpt: 4\njlpt: 5",
            "jlpt:\njlpt: 5",
            "jlpt: []\njlpt: 5",
            "jlpt: '5'\njlpt: 5",
            "jlpt: 5\nnotes: |\n  jlpt: 4",
            "notes: |\n  jlpt: 5",
            "notes: >-\n  jlpt: 5",
            "jlpt:\n  jlpt: 5",
            "jlpt: |\n  jlpt: 5",
            "jlpt: |\n  5",
            "jlpt:\n  - 5",
            "'jlpt': 5",
            "\"jlpt\": 5",
        )) {
            assertTrue(header, invalidWordProblem(header).startsWith("words/いぬ.md: "))
        }
    }

    @Test
    fun wordLevelsDoNotGenerateOrReplaceFreeTags() {
        val tags = listOf("animals", "free-form", "jlpt-n4")
        for (field in listOf("", "jlpt: 5")) {
            val text = wordMd(field).replace("kanji: []", "kanji: []\ntags: [${tags.joinToString(", ")}]")
            val word = ContentParser.parseWord("words/ねこ.md", text)
            assertEquals(tags, word.tags)
            assertEquals(if (field.isEmpty()) null else 5, word.jlpt)
        }
    }

    @Test
    fun kanjiLevelInterpretationRemainsUnchanged() {
        for ((value, expected) in listOf("5" to 5, "'5'" to 5, "\"5\"" to 5, "6" to 6, "" to null, "[]" to null, "null" to null)) {
            assertEquals(value, expected, ContentParser.parseKanji("kanji/日.md", kanjiMd.replace("jlpt: 5", "jlpt: $value")).jlpt)
        }
    }

    private fun invalidWordProblem(header: String): String {
        val invalid = wordMd().replace("kanji: []", "kanji: []\n$header").replace("ねこ", "いぬ")
        val files = sample() + mapOf(
            "words/ねこ.md" to wordMd("jlpt: 5").toByteArray(),
            "words/いぬ.md" to invalid.toByteArray(),
        )
        val parsed = ContentParser.parse(files + ("manifest.json" to manifest(files)))
        assertEquals(header, listOf("ねこ"), parsed.words.map { it.word })
        assertEquals(1, parsed.kanji.size)
        return parsed.problems.single()
    }

    @Test
    fun quizDistractorsAreOptionalCappedAndPrunedWhenTargetsAreMissing() {
        assertEquals(emptyList<String>(), ContentParser.parseWord("words/a.md", quizWord("a")).quizDistractors)
        val parsed = parseQuizWords("a" to "quiz_distractors: [b, c, gone]", "b" to "", "c" to "")
        assertEquals(listOf("b", "c"), parsed.words.first { it.word == "a" }.quizDistractors)
        assertTrue(parsed.problems.single().contains("'quiz_distractors' references missing words: gone"))
        assertThrows("a") { ContentParser.parseWord("words/a.md", quizWord("a", "quiz_distractors: [b, c, d, e]")) }
        assertThrows("a") { ContentParser.parseWord("words/a.md", quizWord("a", "quiz_distractors: [a]")) }
        assertThrows("a") { ContentParser.parseWord("words/a.md", quizWord("a", "quiz_distractors: [b, b]")) }
    }

    private fun assertThrows(@Suppress("UNUSED_PARAMETER") id: String, block: () -> Unit) {
        try {
            block()
        } catch (_: ContentException) {
            return
        }
        org.junit.Assert.fail("expected ContentException")
    }

    private fun quizWord(id: String, header: String = "") =
        "---\nword: $id\nreading: reading-$id\ntitle: meaning-$id\n$header\n---\nbody"

    private fun parseQuizWords(vararg words: Pair<String, String>) =
        (sample() + words.associate { (id, header) -> "words/$id.md" to quizWord(id, header).toByteArray() })
            .let { ContentParser.parse(it + ("manifest.json" to manifest(it))) }

    private fun invalidQuizExclusionProblem(header: String): String {
        val parsed = parseQuizWords("bad" to header, "good" to "", "other" to "")
        assertEquals(header, listOf("good", "other"), parsed.words.map { it.word })
        assertEquals(1, parsed.kanji.size)
        return parsed.problems.single()
    }

    @Test
    fun quizExclusionsAreOptionalFlatStringListsWithoutSynthesizingReverseDeclarations() {
        for (field in listOf("", "quiz_exclusions: []", "quiz_exclusions: [ ]")) {
            assertEquals(emptyList<String>(), ContentParser.parseWord("words/a.md", quizWord("a", field)).quizExclusions)
        }
        val parsed = parseQuizWords(
            "a" to "quiz_exclusions: [b, 'c', \"d\"]", "b" to "", "c" to "", "d" to "",
        )
        assertEquals(emptyList<String>(), parsed.problems)
        assertEquals(listOf("b", "c", "d"), parsed.words.first().quizExclusions)
        assertTrue(parsed.words.drop(1).all { it.quizExclusions.isEmpty() })
        assertEquals(
            listOf("5", "true", "2026-10-09"),
            ContentParser.parseWord("words/a.md", quizWord("a", "quiz_exclusions: ['5', \"true\", '2026-10-09']")).quizExclusions,
        )
    }

    @Test
    fun quizExclusionDeclarationsCannotBeDuplicatedQuotedIndentedOrShadowed() {
        for (header in listOf(
            "quiz_exclusions: []\nquiz_exclusions: []",
            "quiz_exclusions: [other]\nquiz_exclusions: [other]",
            "quiz_exclusions: null\nquiz_exclusions: [other]",
        )) {
            assertEquals(
                header,
                "words/bad.md: 'quiz_exclusions' must be a single top-level unquoted-key inline list",
                invalidQuizExclusionProblem(header),
            )
        }
        for (header in listOf(
            "'quiz_exclusions': [other]",
            "\"quiz_exclusions\": [other]",
            "  quiz_exclusions: [other]",
            "notes: |\n  quiz_exclusions: [other]",
            "quiz_exclusions: [other]\nnotes: >-\n  quiz_exclusions: [other]",
            "quiz_exclusions:\n  quiz_exclusions: [other]",
        )) {
            assertEquals(header, "words/bad.md: $wordKeyProblem", invalidQuizExclusionProblem(header))
        }
    }

    @Test
    fun quizExclusionsRejectMalformedListsAndNonStringScalarsBeforeReferenceChecking() {
        for (value in listOf(
            "", "null", "~", "other", "'other'", "\"[]\"", "{}", "[5]", "[+5]", "[-5]", "[5.0]", "[1e3]", "[0x10]",
            "[0b10]", "[0o10]", "[.nan]", "[-.Inf]", "[true]", "[FALSE]", "[yes]", "[Off]", "[null]", "[~]",
            "[2026-10-09]", "[[]]", "[{}]", "[other,]", "[,other]", "[other,,good]", "[\"unterminated]",
            "['other\", good]", "[\"a,b\"]", "[\"bad\\escape\"]", "[&id other]", "[*id]", "[!!str other]",
            "[other # comment]", "[other] # comment", "|", ">-",
        )) {
            assertEquals(
                value,
                "words/bad.md: 'quiz_exclusions' must be an inline list of strings",
                invalidQuizExclusionProblem("quiz_exclusions: $value"),
            )
        }
    }

    @Test
    fun quizExclusionsRejectDuplicateSelfEmptyAndUnsafeIds() {
        val invalid = listOf(
            "[other, other]" to "contains duplicate IDs",
            "[other, 'other']" to "contains duplicate IDs",
            "[bad]" to "must not contain the word itself",
            "['bad']" to "must not contain the word itself",
            "['']" to "contains an empty or invalid word ID",
            "[' ']" to "contains an empty or invalid word ID",
            "['a/b']" to "contains an empty or invalid word ID",
            "[\"a\u001Fb\"]" to "contains an empty or invalid word ID",
        )
        for ((value, message) in invalid) {
            assertEquals("words/bad.md: 'quiz_exclusions' $message", invalidQuizExclusionProblem("quiz_exclusions: $value"))
        }
    }

    @Test
    fun quizExclusionReferencesUseExactWrittenIdsNotReadingsTitlesOrCaseFolding() {
        for (reference in listOf("missing", "Other", "reading-other", "meaning-other")) {
            assertEquals(
                "words/bad.md: 'quiz_exclusions' references missing words: $reference",
                invalidQuizExclusionProblem("quiz_exclusions: [$reference]"),
            )
        }
    }

    @Test
    fun rejectedWordsCannotLeaveDanglingExclusionsInTheImportedSnapshot() {
        val chain = parseQuizWords("a" to "quiz_exclusions: [b]", "b" to "quiz_exclusions: [missing]", "c" to "quiz_exclusions: [a]", "good" to "")
        assertEquals(listOf("good"), chain.words.map { it.word })
        assertEquals(setOf("words/a.md", "words/b.md", "words/c.md"), chain.problems.map { it.substringBefore(':') }.toSet())
        val malformed = parseQuizWords("a" to "quiz_exclusions: [b]", "b" to "jlpt: 0", "good" to "")
        assertEquals(listOf("good"), malformed.words.map { it.word })
        assertEquals(2, malformed.problems.size)
        assertTrue(malformed.problems.contains("words/a.md: 'quiz_exclusions' references missing words: b"))
    }

    @Test
    fun quizExclusionScanningIgnoresCommentsUnrelatedScalarTextAndKanjiFields() {
        val word = ContentParser.parseWord(
            "words/a.md", quizWord("a", "# quiz_exclusions: [missing]\nnotes: quiz_exclusions: [missing]"),
        )
        assertTrue(word.quizExclusions.isEmpty())
        val kanji = kanjiMd.replace("jlpt: 5", "jlpt: 5\nquiz_exclusions: null\n'quiz_exclusions': [5]")
        assertEquals(5, ContentParser.parseKanji("kanji/日.md", kanji).jlpt)
    }

    private val wordKeyProblem =
        "word header keys must be unquoted, unindented ASCII identifiers matching [A-Za-z_][A-Za-z0-9_-]*"

    private fun unsupportedMetadataKeys(key: String): List<String> {
        val hex = key.first().code.toString(16)
        val rest = key.drop(1)
        return listOf(
            "\"\\u${hex.padStart(4, '0')}$rest\"",
            "\"\\x$hex$rest\"",
            "\"\\U${hex.padStart(8, '0')}$rest\"",
            "\"$key\"", "'$key'", "!!str $key", "!<tag:yaml.org,2002:str> $key",
            "&alias $key", "!!str &alias $key", "*alias", "? $key", "[$key]", "{$key: alias}",
        )
    }

    @Test
    fun encodedAndTaggedQuizKeysCannotBeHiddenByCanonicalEmptyOverride() {
        for (key in unsupportedMetadataKeys("quiz_exclusions")) {
            for (header in listOf(
                "$key: [other]\nquiz_exclusions: []",
                "quiz_exclusions: []\n$key: [other]",
                "$key: []",
            )) {
                assertEquals(header, "words/bad.md: $wordKeyProblem", invalidQuizExclusionProblem(header))
            }
        }
    }

    @Test
    fun encodedAndTaggedJlptKeysCannotBeHiddenByCanonicalValue() {
        for (key in unsupportedMetadataKeys("jlpt")) {
            for (header in listOf("$key: 1\njlpt: 5", "jlpt: 5\n$key: 1", "$key: 5")) {
                assertEquals(header, "words/いぬ.md: $wordKeyProblem", invalidWordProblem(header))
            }
        }
    }

    @Test
    fun unsupportedSyntaxIsRejectedForEveryWordHeaderKey() {
        val keys = unsupportedMetadataKeys("notes") + listOf(
            " notes", "\tnotes", "ノート", "1notes", "-notes", "notes.dot", "notes value", "\uFEFFnotes", "\u200Bnotes",
        )
        for (key in keys) {
            val header = "$key: ignored\njlpt: 5\nquiz_exclusions: []"
            assertEquals(header, "words/bad.md: $wordKeyProblem", invalidQuizExclusionProblem(header))
        }
        for (header in listOf(": ignored", "? [notes]\n: ignored")) {
            assertEquals(header, "words/bad.md: $wordKeyProblem", invalidQuizExclusionProblem(header))
        }
    }

    @Test
    fun plainWordKeysRetainWhitespaceCommentsAndQuotedValues() {
        val header = "# \"\\u0071uiz_exclusions\": [missing]\n" +
            " \t# !!str jlpt: 0\n" +
            "_Metadata-2 \t: '\"\\u0071uiz_exclusions\": [missing]'\n" +
            "TagText: \"!!str jlpt: 0\"\n" +
            "AnchorText: '&alias quiz_exclusions: [missing]'\n" +
            "UriText: '!<tag:yaml.org,2002:str> jlpt: 0'\n" +
            "jlpt \t: 5\nquiz_exclusions \t: ['other']"
        for (closing in listOf("---", "--- \t", "--- # closing comment")) {
            val text = quizWord("bad", header).replace("\n---\nbody", "\n$closing\nbody")
            val word = ContentParser.parseWord("words/bad.md", text)
            assertEquals(5, word.jlpt)
            assertEquals(listOf("other"), word.quizExclusions)
            assertEquals("meaning-bad", word.title)
            assertEquals("body", word.body)
        }
    }

    @Test
    fun wordKeyRestrictionDoesNotChangeKanjiLegacyKeys() {
        val expected = ContentParser.parseKanji("kanji/日.md", kanjiMd)
        for (key in unsupportedMetadataKeys("jlpt") + listOf(" notes", "ノート", "1notes")) {
            val text = kanjiMd.replace("jlpt: 5", "$key: ignored\njlpt: 5")
            assertEquals(key, expected, ContentParser.parseKanji("kanji/日.md", text))
        }
        assertEquals(6, ContentParser.parseKanji("kanji/日.md", kanjiMd.replace("jlpt: 5", "  jlpt: '6'")).jlpt)
    }

    @Test
    fun wordKeyValidationPrecedesKnownDuplicateFieldErrors() {
        val header = "jlpt: 5\njlpt: 4\nquiz_exclusions: [other]\nquiz_exclusions: []\n\"\\u006eotes\": ignored"
        assertEquals("words/bad.md: $wordKeyProblem", invalidQuizExclusionProblem(header))
    }

    @Test
    fun lookalikeClosingMarkerCannotHideUnsupportedWordKeys() {
        for (marker in listOf("---not-a-delimiter: ignored", "---: ignored", "----: ignored", "--- notes: ignored", "---#not-separated: ignored")) {
            val header = "$marker\n\"\\u0071uiz_exclusions\": [other]\nquiz_exclusions: []"
            assertEquals(
                header,
                "words/bad.md: word front matter must end with a standalone '---' delimiter",
                invalidQuizExclusionProblem(header),
            )
        }
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
