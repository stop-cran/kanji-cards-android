package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.Manifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KanjiMeaningMetadataTest {
    private fun markdown(id: String, extra: String = "", title: String = id) =
        "---\nkanji: $id\ntitle: $title\njlpt: 5\n$extra\n---\nbody"

    private fun parse(vararg cards: Pair<String, String>) = cards.associate { (id, text) ->
        "kanji/$id.md" to text.toByteArray()
    }.let { files ->
        val manifest = Manifest(1, "test", files.mapValues { ContentParser.sha256(it.value) })
        ContentParser.parse(files + ("manifest.json" to Json.encodeToString(Manifest.serializer(), manifest).toByteArray()))
    }

    @Test
    fun absentEmptyAndSupplementaryCharacterListsPreserveLegacyMetadata() {
        for (extra in listOf("", "quiz_exclusions: []", "quiz_exclusions: [ ]")) {
            assertTrue(ContentParser.parseKanji("kanji/日.md", markdown("日", extra)).quizExclusions.isEmpty())
        }
        val parsed = parse(
            "日" to markdown("日", "quiz_exclusions: ['𠮷']"),
            "𠮷" to markdown("𠮷"),
        )
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertEquals(listOf("𠮷"), parsed.kanji.first().quizExclusions)
        assertEquals(5, parsed.kanji.first().jlpt)
    }

    @Test
    fun malformedUnsafeDuplicateSelfAndNonCharacterIdsAreRejected() {
        val invalid = listOf(
            "", "null", "true", "日", "[日", "[月,]", "[月, 月]", "[日]", "[日月]", "['']",
            "[5]", "[true]", "['/']", "['\\\\']", "['\\u6708']", "[!str 月]", "[&id 月]", "[*id]",
            "[' ']", "['\u00a0']", "['\u001f']", "['\uD800']", "['\uFDD0']", "['\uFFFF']", "['\uDBFF\uDFFF']",
        )
        for (value in invalid) {
            assertThrows(value, ContentException::class.java) {
                ContentParser.parseKanji("kanji/日.md", markdown("日", "quiz_exclusions: $value"))
            }
        }
    }

    @Test
    fun duplicateShadowedQuotedEncodedTaggedAndAliasedDeclarationsAreRejected() {
        val invalid = listOf(
            "quiz_exclusions: []\nquiz_exclusions: [月]",
            "notes: |\n  quiz_exclusions: [月]",
            "'quiz_exclusions': [月]",
            "\"quiz_\\u0065xclusions\": [月]",
            "\"\\x71uiz_exclusions\": [月]",
            "!!str quiz_exclusions: [月]",
            "!<tag:example> quiz_exclusions: [月]",
            "&key !<tag:example> quiz_exclusions: [月]",
            "&key quiz_exclusions: [月]",
            "key: &guard quiz_exclusions\n*guard: [月]",
            "key: &guard 'quiz_exclusions' # alias key\n*guard: [月]",
            "key: &guard quiz_exclusions\nnext: &second *guard\n*second: [月]",
            "? quiz_exclusions: [月]",
            "quiz_exclusions: &guard [月]",
        )
        for (declaration in invalid) {
            assertThrows(declaration, ContentException::class.java) {
                ContentParser.parseKanji("kanji/日.md", markdown("日", declaration))
            }
        }
        assertTrue(ContentParser.parseKanji("kanji/日.md",
            markdown("日", "# quiz_exclusions: null\nnotes: quiz_exclusions: null\njlpt: 4")).quizExclusions.isEmpty())
    }

    @Test
    fun quotedAliasKeysRejectAdjacentCommentsWithoutChangingUnrelatedQuotedScalars() {
        for (quote in listOf("'", "\"")) {
            val declaration = "key: &guard ${quote}quiz_exclusions${quote}#comment\n*guard: [月]"
            assertThrows(declaration, ContentException::class.java) {
                ContentParser.parseKanji("kanji/日.md", markdown("日", declaration))
            }
            val unrelated = "key: &guard ${quote}quiz_exclusions #literal${quote}\n*guard: [月]"
            val card = ContentParser.parseKanji("kanji/日.md", markdown("日", unrelated))
            assertTrue(card.quizExclusions.isEmpty())
            assertEquals(5, card.jlpt)
        }
    }

    @Test
    fun rejectedReferenceCascadesAreCheckedAfterDuplicateTitleRemovalAcrossFullCorpus() {
        val parsed = parse(
            "一" to markdown("一"),
            "月" to markdown("月", title = "一"),
            "日" to markdown("日", "quiz_exclusions: [月]"),
            "火" to markdown("火", "quiz_exclusions: [日]"),
            "水" to markdown("水", "quiz_exclusions: [未]"),
            "木" to markdown("木", "quiz_exclusions: [水]"),
        )
        assertEquals(listOf("一"), parsed.kanji.map { it.kanji })
        assertEquals(5, parsed.problems.size)
        assertTrue(parsed.problems.any { "kanji/火.md" in it && "missing kanji: 日" in it })
        val valid = parse("日" to markdown("日", "quiz_exclusions: [月]"), "月" to markdown("月"))
        assertEquals(2, valid.kanji.size)
    }

    @Test
    fun missingIdsAreCaseExactAndRejectedSyntaxAlsoCascadesWithoutTighteningLegacyIds() {
        val valid = parse("A" to markdown("A"), "日" to markdown("日", "quiz_exclusions: [A]"))
        assertEquals(2, valid.kanji.size)
        val invalid = parse(
            "A" to markdown("A"),
            "日" to markdown("日", "quiz_exclusions: [a]"),
            "月" to markdown("月", "quiz_exclusions: [日]"),
            "火" to markdown("火", "quiz_exclusions: null"),
            "水" to markdown("水", "quiz_exclusions: [火]"),
        )
        assertEquals(listOf("A"), invalid.kanji.map { it.kanji })
        assertEquals(4, invalid.problems.size)
    }
}
