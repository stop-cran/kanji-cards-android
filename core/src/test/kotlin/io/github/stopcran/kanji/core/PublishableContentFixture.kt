package io.github.stopcran.kanji.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object PublishableContentFixture {
    fun load(root: File): Map<String, ByteArray> {
        val git = ProcessBuilder("git", "-C", root.absolutePath, "ls-files", "-c", "-o",
            "--exclude-standard", "-z", "--", ":(exclude,attr:export-ignore)")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = git.inputStream.use { it.readBytes() }
        check(git.waitFor() == 0) { "git ls-files failed for publication fixture: $root" }
        val paths = decodePaths(output).toSet()
        check("manifest.json" in paths) { "Publication fixture excludes manifest.json" }
        val manifestBytes = File(root, "manifest.json").readBytes()
        val manifest = Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
        val required = manifest.values.filterIsInstance<JsonObject>().flatMap { it.keys }.toSet()
        val missing = required - paths
        check(missing.isEmpty()) { "Publication fixture excludes manifest dependencies: ${missing.sorted()}" }
        return paths.associateWith { path ->
            if (path == "manifest.json") manifestBytes else File(root, path).readBytes()
        }
    }

    fun decodePaths(output: ByteArray): List<String> =
        output.decodeToString().split('\u0000').filter { it.isNotEmpty() }
}
