package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentException
import io.github.stopcran.kanji.core.content.ContentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentLanguageTest {
    private val kanji = """
        ---
        kanji: 日
        title: sun, day
        jlpt: 5
        tags: [jlpt-n5, grade-1]
        strokes: 4
        onyomi: [ニチ, ジツ]
        kunyomi: [ひ, -び]
        ---
        # 日
    """.trimIndent()

    private val strokes = """
        {"schemaVersion":1,"kanji":"日","viewBox":[0,0,109,109],"strokeCount":1,
         "strokes":[{"id":1,"type":"a","points":[[1,2],[3,4]]}]}
    """.trimIndent()

    private fun files() = mapOf(
        "kanji/日.md" to kanji.toByteArray(),
        "strokes/日.json" to strokes.toByteArray(),
        "articles/x.md" to "# Title X\ntext".toByteArray(),
    )

    private fun manifest(
        files: Map<String, ByteArray>,
        schema: Int,
        vararg languageFields: Pair<String, String?>,
    ): ByteArray {
        val entries = files.entries.joinToString(",") { (path, bytes) ->
            "\"$path\":\"${ContentParser.sha256(bytes)}\""
        }
        val metadata = languageFields.joinToString(",") { (name, value) ->
            val encoded = value?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null"
            "\"$name\":$encoded"
        }.let { if (it.isEmpty()) "" else "$it," }
        return """
            {"schemaVersion":$schema,${metadata}"contentVersion":"v1",
             "files":{$entries}}
        """.trimIndent().toByteArray()
    }

    @Test
    fun legacyEnglishExplicitEnglishAndRussianLanguageSchemasAreAccepted() {
        val files = files()
        val legacy = ContentParser.parse(files + ("manifest.json" to manifest(files, 1))).manifest
        assertEquals("en", legacy.baseLanguage)
        assertEquals("ja", legacy.targetLanguage)
        assertEquals(1, legacy.schemaVersion)

        val english = ContentParser.parse(
            files + ("manifest.json" to manifest(files, 1, "baseLanguage" to "en", "targetLanguage" to "ja")),
        ).manifest
        assertEquals("en", english.baseLanguage)
        assertEquals("ja", english.targetLanguage)

        val russian = ContentParser.parse(
            files + ("manifest.json" to manifest(files, 2, "baseLanguage" to "ru", "targetLanguage" to "ja")),
        ).manifest
        assertEquals("ru", russian.baseLanguage)
        assertEquals("ja", russian.targetLanguage)
    }

    @Test
    fun unsupportedOrIncompleteLanguageSchemaPairsFailClosed() {
        val files = files()
        val invalid = listOf(
            manifest(files, 1, "baseLanguage" to "ru", "targetLanguage" to "ja"),
            manifest(files, 2, "baseLanguage" to "en", "targetLanguage" to "ja"),
            manifest(files, 2, "baseLanguage" to "ru", "targetLanguage" to "en"),
            manifest(files, 1, "baseLanguage" to "en"),
            manifest(files, 1, "targetLanguage" to "ja"),
            manifest(files, 1, "baseLanguage" to null, "targetLanguage" to null),
            manifest(files, 2),
        )
        invalid.forEach { encoded ->
            assertThrows(ContentException::class.java) {
                ContentParser.parse(files + ("manifest.json" to encoded))
            }
        }
    }

    @Test
    fun duplicateRootLanguageContractKeysAreRejectedBeforeJsonObjectDecoding() {
        val files = files()
        val explicitEnglish = manifest(files, 1, "baseLanguage" to "en", "targetLanguage" to "ja").decodeToString()
        val duplicateEqual = explicitEnglish.replace(
            "\"baseLanguage\":\"en\"",
            "\"baseLanguage\":\"en\",\"baseLanguage\":\"en\"",
        )
        val duplicateEscaped = explicitEnglish.replace(
            "\"baseLanguage\":\"en\"",
            "\"baseLanguage\":\"en\",\"base\\u004canguage\":\"en\"",
        )
        val duplicateSchema = explicitEnglish.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1")
        val duplicateEqualSchema = explicitEnglish.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
        val duplicateTarget = explicitEnglish.replace("\"targetLanguage\":\"ja\"", "\"targetLanguage\":\"ja\",\"targetLanguage\":\"ja\"")
        listOf(duplicateEqual, duplicateEscaped, duplicateSchema, duplicateEqualSchema, duplicateTarget).forEach { encoded ->
            assertThrows(ContentException::class.java) {
                ContentParser.parse(files + ("manifest.json" to encoded.toByteArray()))
            }
        }

        assertEquals(
            "en",
            ContentParser.parse(files + ("manifest.json" to explicitEnglish.toByteArray())).manifest.baseLanguage,
        )
    }

    @Test
    fun v1CounterTranslationAcceptsNeutralAndLegacyEnglishFieldsButRejectsAmbiguousPairs() {
        val path = "counters/count.json"
        assertEquals("one book", ContentParser.parseCounter(path, counterJson("en")).cards.single().en)
        assertEquals("one book", ContentParser.parseCounter(path, counterJson("translation")).cards.single().en)
        assertEquals("one book", ContentParser.parseCounter(path, counterJson("translation"), "ru").cards.single().en)

        assertThrows(IllegalArgumentException::class.java) {
            ContentParser.parseCounter(path, counterJson("en", "translation"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentParser.parseCounter(path, counterJson("en"), "ru")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentParser.parseCounter(path, counterJson("translation", "en"), "ru")
        }
    }

    private fun counterJson(field: String, alsoField: String? = null): String {
        val extra = alsoField?.let { ",\"$it\":\"ignored\"" }.orEmpty()
        return """
            {"schemaVersion":1,"id":"count","counter":"本","reading":"ほん","title":"Long objects",
             "cards":[{"id":"count-1","text":"本を{}","$field":"one book"$extra,
              "noun":"本","phrase":"一本","reading":"いっぽん","good":["本"],
              "distractors":["枚","人","個"]}]}
        """.trimIndent()
    }
}
