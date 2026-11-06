package io.github.stopcran.kanji.core

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublishableContentFixtureTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun keepsTrackedAndUntrackedPublicationFilesWithoutReadingContributorEvidence() {
        val root = repository()
        write(root, "manifest.json", """{"files":{"kanji/\u65e5.md":"hash"}}""")
        val kanji = "kanji/\u65e5.md"
        write(root, kanji, "tracked kanji")
        git(root, "add", "--", "manifest.json", kanji, ".gitattributes")
        write(root, "words/extra word.md", "unmanifested local addition")
        write(root, ".gitignore", "ignored/\n")
        write(root, "ignored/local.bin", "ignored local data")
        write(root, "docs/reviews/nested/evidence.txt", "preserved evidence")
        write(root, "tools/local.py", "contributor tool")
        write(root, ".github/workflows/test.yml", "contributor workflow")
        git(root, "add", "--", "docs", "tools", ".github")

        val files = PublishableContentFixture.load(root)

        assertEquals(setOf("manifest.json", kanji, "words/extra word.md", ".gitattributes", ".gitignore"), files.keys)
        assertArrayEquals("tracked kanji".toByteArray(), files.getValue(kanji))
        assertArrayEquals("unmanifested local addition".toByteArray(), files.getValue("words/extra word.md"))
        assertEquals("preserved evidence", File(root, "docs/reviews/nested/evidence.txt").readText())
    }

    @Test
    fun rejectsExportIgnoredDependenciesInEveryManifestMap() {
        val root = repository()
        val path = "docs/required.json"
        write(root, path, "{}")
        for (section in listOf("files", "images", "scenes", "listening", "audio", "curriculum",
            "listeningTasks", "listeningTaskAudio", "listeningTaskImages")) {
            write(root, "manifest.json", JsonObject(mapOf(
                section to JsonObject(mapOf(path to JsonPrimitive("hash"))),
            )).toString())
            val error = assertThrows(IllegalStateException::class.java) {
                PublishableContentFixture.load(root)
            }
            assertTrue(section, error.message.orEmpty().contains(path))
        }
    }

    @Test
    fun rejectsExportIgnoredManifest() {
        val root = repository()
        write(root, "manifest.json", """{"files":{}}""")
        File(root, ".gitattributes").appendText("/manifest.json export-ignore\n")
        val error = assertThrows(IllegalStateException::class.java) {
            PublishableContentFixture.load(root)
        }
        assertTrue(error.message.orEmpty().contains("excludes manifest.json"))
    }

    @Test
    fun failsWhenGitCannotListTheRepository() {
        val root = temporary.newFolder()
        write(root, "manifest.json", """{"files":{}}""")
        val error = assertThrows(IllegalStateException::class.java) {
            PublishableContentFixture.load(root)
        }
        assertTrue(error.message.orEmpty().contains("git ls-files failed"))
    }

    @Test
    fun preservesUnicodeSpacesAndEmbeddedLineBreaksInNulDelimitedPaths() {
        val paths = listOf("kanji/\u65e5.md", "words/with space.md", "articles/line\nbreak\tname.md")
        val output = (paths.joinToString("\u0000") + "\u0000").toByteArray()
        assertEquals(paths, PublishableContentFixture.decodePaths(output))
    }

    private fun repository(): File = temporary.newFolder().also { root ->
        git(root, "init", "--quiet")
        write(root, ".gitattributes", """
            /docs export-ignore
            /docs/** export-ignore
            /tools export-ignore
            /tools/** export-ignore
            /.github export-ignore
            /.github/** export-ignore
        """.trimIndent() + "\n")
    }

    private fun write(root: File, path: String, text: String) {
        File(root, path).also {
            it.parentFile.mkdirs()
            it.writeText(text)
        }
    }

    private fun git(root: File, vararg arguments: String) {
        val command = ProcessBuilder(listOf("git", "-C", root.absolutePath) + arguments)
            .redirectErrorStream(true).start()
        val output = command.inputStream.use { it.readBytes().decodeToString() }
        assertEquals(output, 0, command.waitFor())
    }
}
