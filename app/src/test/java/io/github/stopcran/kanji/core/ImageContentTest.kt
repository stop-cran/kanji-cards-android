package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
import io.github.stopcran.kanji.core.content.Manifest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageContentTest {
    private fun webp(extra: Int = 8): ByteArray {
        val size = 4 + extra
        val head = "RIFF".toByteArray() + ByteArray(4) { ((size shr (8 * it)) and 0xFF).toByte() }
        return head + "WEBP".toByteArray() + ByteArray(extra) { 0x0D.toByte() }
    }

    private fun word(name: String, image: String?): ByteArray {
        val extra = if (image != null) "image: $image\nimage_scene: A scene\n" else ""
        return "---\nword: $name\nreading: $name\ntitle: thing\nkanji: []\n$extra---\n\n# $name\n".toByteArray()
    }

    private data class Catalogs(
        val files: Map<String, ByteArray> = emptyMap(),
        val hashes: Map<String, String> = files.mapValues { ContentParser.sha256(it.value) },
        val misplaced: Boolean = false,
    )

    private fun parse(
        words: Map<String, String?>,
        images: Map<String, ByteArray>,
        hashes: Map<String, String> = images.mapValues { ContentParser.rawSha256(it.value) },
        catalogs: Catalogs = Catalogs(),
        imageValidator: (ByteArray) -> String? = { null },
    ): io.github.stopcran.kanji.core.content.ParsedContent {
        val text = words.entries.associate { (n, img) -> "words/$n.md" to word(n, img) } +
            if (catalogs.misplaced) catalogs.files else emptyMap()
        val manifest = Manifest(1, "v", text.mapValues { ContentParser.sha256(it.value) }, hashes, catalogs.hashes)
        val files = text + images + catalogs.files + ("manifest.json" to Json.encodeToString(Manifest.serializer(), manifest).toByteArray())
        return ContentParser.parse(files, imageValidator)
    }

    @Test
    fun legacyImageEnvelopeIsKeptAndRawBytesAreHashedUnnormalised() {
        val bytes = webp()
        val p = parse(mapOf("ぽか" to "images/ぽか.webp"), mapOf("images/ぽか.webp" to bytes))
        assertEquals(emptyList<String>(), p.problems)
        assertEquals("images/ぽか.webp", p.words.single().image)
        assertEquals("A scene", p.words.single().imageScene)
        assertEquals("images/ぽか.webp", p.words.single().quizImage)
        assertEquals("legacy", p.words.single().scenes.single().id)
        assertTrue(bytes.contentEquals(p.images.getValue("images/ぽか.webp")))
    }

    @Test
    fun badHashDropsOnlyThePicture() {
        val p = parse(
            mapOf("ぽか" to "images/ぽか.webp"),
            mapOf("images/ぽか.webp" to webp()),
            hashes = mapOf("images/ぽか.webp" to "0".repeat(64)),
        )
        assertEquals(1, p.words.size)
        assertNull(p.words.single().image)
        assertTrue(p.images.isEmpty())
        assertTrue(p.problems.any { "hash mismatch" in it })
    }

    @Test
    fun nonWebpAndOversizeImagesAreRejected() {
        val bad = "PNG....".toByteArray() + ByteArray(20)
        val huge = webp(extra = 160 * 1024)
        val p = parse(
            mapOf("a" to "images/a.webp", "b" to "images/b.webp"),
            mapOf("images/a.webp" to bad, "images/b.webp" to huge),
        )
        assertTrue(p.images.isEmpty())
        assertTrue(p.words.all { it.image == null })
        assertTrue(p.problems.any { "not a WebP" in it })
        assertTrue(p.problems.any { "larger than" in it })
    }

    @Test
    fun truncatedWebpIsRejected() {
        val whole = webp()
        val p = parse(mapOf("a" to "images/a.webp"), mapOf("images/a.webp" to whole.copyOf(whole.size - 3)))
        assertTrue(p.images.isEmpty())
        assertTrue(p.problems.any { "not a WebP" in it })
    }

    @Test
    fun secondOwnerOfAnImageLosesIt() {
        val p = parse(
            mapOf("a" to "images/x.webp", "b" to "images/x.webp"),
            mapOf("images/x.webp" to webp()),
        )
        assertEquals(1, p.words.count { it.image != null })
        assertTrue(p.problems.any { "already used" in it })
    }

    @Test
    fun missingImageFileOrNestedPathIsRejected() {
        val p = parse(mapOf("a" to "images/a.webp", "b" to "images/sub/b.webp"), emptyMap())
        assertTrue(p.words.all { it.image == null })
        assertTrue(p.problems.isNotEmpty())
    }

    private fun scene(id: String = "warmth", image: String = "images/a.webp", eligible: String = "true") =
        """{"id":"$id","sense":"Warmth","image":"$image","description":"A scene","quizEligible":$eligible}"""

    private fun catalog(vararg scenes: String) =
        """{"schemaVersion":1,"word":"a","scenes":[${scenes.joinToString(",")}]}""".toByteArray()

    @Test
    fun catalogKeepsNamedVariantsAndArticleOnlyImagesWithoutDuplicatingLegacyOwnership() {
        val p = parse(
            mapOf("a" to "images/a.webp"),
            mapOf("images/a.webp" to webp(), "images/b.webp" to webp(9)),
            catalogs = Catalogs(mapOf("scenes/a.json" to catalog(scene(), scene("diagram", "images/b.webp", "false")))),
        )
        assertEquals(emptyList<String>(), p.problems)
        assertEquals(listOf("warmth", "diagram"), p.words.single().scenes.map { it.id })
        assertEquals(listOf(true, false), p.words.single().scenes.map { it.quizEligible })
        assertEquals("images/a.webp", p.words.single().quizImage)
        assertEquals(2, p.images.size)
    }

    @Test
    fun articleOnlyCatalogWithoutLegacyFieldsKeepsImageButHasNoQuizImage() {
        val p = parse(
            mapOf("a" to null), mapOf("images/a.webp" to webp()),
            catalogs = Catalogs(mapOf("scenes/a.json" to catalog(scene(eligible = "false")))),
        )
        assertEquals(emptyList<String>(), p.problems)
        assertEquals("images/a.webp", p.words.single().image)
        assertNull(p.words.single().quizImage)
        assertEquals(1, p.images.size)
    }

    @Test
    fun declaredMissingCorruptOrMisplacedCatalogNeverFallsBackToLegacyQuizEligibility() {
        val words = mapOf("a" to "images/a.webp")
        val images = mapOf("images/a.webp" to webp())
        val catalogs = mapOf("scenes/a.json" to catalog(scene()))
        val results = listOf(
            parse(words, images, catalogs = Catalogs(hashes = mapOf("scenes/a.json" to "0".repeat(64)))),
            parse(words, images, catalogs = Catalogs(catalogs, mapOf("scenes/a.json" to "0".repeat(64)))),
            parse(words, images, catalogs = Catalogs(mapOf("scenes/a.json" to "{".toByteArray()))),
            parse(words, images, catalogs = Catalogs(catalogs, misplaced = true)),
            parse(words, images, catalogs = Catalogs(catalogs, hashes = emptyMap(), misplaced = true)),
        )
        for (p in results) {
            assertEquals("images/a.webp", p.words.single().image)
            assertNull(p.words.single().quizImage)
            assertTrue(p.words.single().scenes.isEmpty())
            assertTrue(p.problems.toString(), p.problems.any { it.startsWith("scenes/a.json:") })
        }
    }

    @Test
    fun eligibilityMustBeExplicitAndLegacyFallbackMustAgree() {
        val invalid = listOf("\"true\"", "null", "1", "[]", "{}").map { catalog(scene(eligible = it)) } +
            catalog(scene().replace(",\"quizEligible\":true", "")) +
            catalog(scene(eligible = "false")) +
            catalog(scene().replace("A scene", "A different caption"))
        for (bytes in invalid) {
            val p = parse(
                mapOf("a" to "images/a.webp"), mapOf("images/a.webp" to webp()),
                catalogs = Catalogs(mapOf("scenes/a.json" to bytes)),
            )
            assertNull(bytes.decodeToString(), p.words.single().quizImage)
            assertTrue(p.problems.isNotEmpty())
        }
    }

    @Test
    fun invalidIdentitiesPathsAndDuplicateVariantsRejectTheCatalogNotTheWord() {
        val invalid = listOf(
            catalog(scene(), scene()),
            catalog(scene(), scene("other")),
            catalog(scene("not a stable id")),
            catalog(scene(image = "images/nested/a.webp")),
            catalog(scene()).decodeToString().replace("\"word\":\"a\"", "\"word\":\"b\"").toByteArray(),
        )
        for (bytes in invalid) {
            val p = parse(
                mapOf("a" to "images/a.webp"), mapOf("images/a.webp" to webp()),
                catalogs = Catalogs(mapOf("scenes/a.json" to bytes)),
            )
            assertEquals(1, p.words.size)
            assertNull(p.words.single().quizImage)
            assertTrue(p.problems.isNotEmpty())
        }
    }

    @Test
    fun missingSceneAssetDropsOnlyThatVariantAndCodecRejectionKeepsLexicalContent() {
        val words = mapOf("a" to null)
        val images = mapOf("images/a.webp" to webp())
        val catalogs = Catalogs(mapOf("scenes/a.json" to catalog(scene(), scene("missing", "images/missing.webp"))))
        val partial = parse(words, images, catalogs = catalogs)
        assertEquals(listOf("warmth"), partial.words.single().scenes.map { it.id })
        assertEquals("images/a.webp", partial.words.single().quizImage)
        assertTrue(partial.problems.any { "images/missing.webp" in it })

        val rejected = parse(words, images, catalogs = catalogs, imageValidator = { "WebP decode failed" })
        assertEquals(1, rejected.words.size)
        assertNull(rejected.words.single().quizImage)
        assertTrue(rejected.images.isEmpty())
        assertTrue(rejected.problems.any { "WebP decode failed" in it })
    }
}
