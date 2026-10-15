package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.ContentParser
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

    private fun parse(
        words: Map<String, String?>,
        images: Map<String, ByteArray>,
        hashes: Map<String, String> = images.mapValues { ContentParser.rawSha256(it.value) },
    ): io.github.stopcran.kanji.core.content.ParsedContent {
        val files = words.entries.associate { (n, img) -> "words/$n.md" to word(n, img) } + images
        val text = files.filterKeys { it.endsWith(".md") }
            .entries.joinToString(",") { (p, b) -> "\"$p\":\"${ContentParser.sha256(b)}\"" }
        val img = hashes.entries.joinToString(",") { (p, h) -> "\"$p\":\"$h\"" }
        val manifest = """{"schemaVersion":1,"contentVersion":"v","files":{$text},"images":{$img}}""".toByteArray()
        return ContentParser.parse(files.filterKeys { it.endsWith(".md") } + images + ("manifest.json" to manifest))
    }

    @Test
    fun validImageIsKeptAndRawBytesAreHashedUnnormalised() {
        val bytes = webp()
        val p = parse(mapOf("ぽか" to "images/ぽか.webp"), mapOf("images/ぽか.webp" to bytes))
        assertEquals(emptyList<String>(), p.problems)
        assertEquals("images/ぽか.webp", p.words.single().image)
        assertEquals("A scene", p.words.single().imageScene)
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
}
