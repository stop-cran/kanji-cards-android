package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.ui.RareHan
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RareHanTest {
    // Code points in res/font/rare_han_*.otf; regenerate with tools/make_rare_font.py when this fails.
    private val covered = setOf(
        0x2E8D, 0x2EAE, 0x2ECA, 0x353E, 0x3AC3, 0x4491, 0x20087, 0x20089, 0x201A2, 0x20B9F, 0x20BD1,
    )

    @Test
    fun contentRareCharactersAreCoveredByBundledFont() {
        val root = File("../../learning-japanese")
        assumeTrue(root.isDirectory)
        val used = mutableSetOf<Int>()
        for (dir in listOf("kanji", "words", "articles")) {
            root.resolve(dir).listFiles { f -> f.extension == "md" }.orEmpty().forEach { f ->
                f.readText().codePoints().filter { RareHan.isRare(it) }.forEach { used += it }
            }
        }
        val missing = used - covered
        assertTrue("Rare characters without a glyph: ${missing.map { "U+%04X".format(it) }}", missing.isEmpty())
    }
}
