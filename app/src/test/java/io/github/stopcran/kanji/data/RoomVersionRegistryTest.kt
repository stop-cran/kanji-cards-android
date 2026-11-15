package io.github.stopcran.kanji.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class RoomVersionRegistryTest {
    private data class Entry(val version: Int, val identity: String, val exported: Boolean) {
        val key get() = version to identity
    }

    private val knownCollisions = mapOf(
        22 to setOf("bb4bd6ed0ae5d551d26ef9d695be95b2", "c15c3d949cdafd3afc602dec4a00e947"),
        23 to setOf("a784ae19620761de81f6d70367395585", "51223af7be5dcde053f7428cc9952cc1"),
    )

    private fun registryEntries(markdown: String): List<Entry> {
        val lines = markdown.lineSequence().filter { it.startsWith("|") }.toList()
        require(lines.size >= 3 && lines[0] == "| Version | Identity hash | Branch or source | Commit | Status | Export |") {
            "Room version registry table is missing or malformed"
        }
        val entries = lines.drop(2).map { line ->
            val cells = line.removePrefix("|").removeSuffix("|").split('|').map(String::trim)
            require(cells.size == 6 && cells.all(String::isNotEmpty)) { "Malformed registry row: $line" }
            val version = cells[0].toIntOrNull()
            val identity = cells[1]
            require(version != null && version > 0) { "Invalid version in registry row: $line" }
            require(identity.matches(Regex("[0-9a-f]{32}")) || identity == "pending") { "Invalid identity in registry row: $line" }
            require(cells[4] in setOf("released", "reserved", "abandoned", "test-only")) { "Invalid status in registry row: $line" }
            require(identity != "pending" || cells[4] == "reserved") { "Pending identities must be reserved: $line" }
            require(cells[5] in setOf("yes", "no")) { "Invalid export marker in registry row: $line" }
            require(identity != "pending" || cells[5] == "no") { "A pending identity cannot have an export: $line" }
            Entry(version, identity, cells[5] == "yes")
        }
        require(entries.map { it.key }.distinct().size == entries.size) { "Duplicate version and identity in registry" }
        entries.groupBy { it.version }.forEach { (version, rows) ->
            if (rows.size > 1) {
                require(rows.map { it.identity }.toSet() == knownCollisions[version]) {
                    "Unreconciled Room version collision: $version"
                }
            }
        }
        return entries
    }

    private fun checkExport(filename: String, contents: String, entries: List<Entry>): Pair<Int, String> {
        val fileVersion = filename.removeSuffix(".json").toIntOrNull()
        require(filename.endsWith(".json") && fileVersion != null && fileVersion > 0) {
            "Invalid Room schema filename: $filename"
        }
        val database = Json.parseToJsonElement(contents).jsonObject.getValue("database").jsonObject
        val version = database.getValue("version").jsonPrimitive.int
        val identity = database.getValue("identityHash").jsonPrimitive.content
        require(fileVersion == version) { "$filename embeds version $version" }
        val key = version to identity
        require(entries.any { it.key == key && it.exported }) {
            "$filename identity $identity is not registered as an export for version $version"
        }
        return key
    }

    private fun checkRequiredExports(entries: List<Entry>, found: Set<Pair<Int, String>>) {
        val missing = entries.filter { it.exported }.map { it.key }.toSet() - found
        require(missing.isEmpty()) { "Registered Room exports are missing: $missing" }
    }

    @Test
    fun allExportedSchemasMatchTheRegistry() {
        val registry = File(File("..", "docs"), "DB-VERSIONS.md")
        val entries = registryEntries(registry.readText())
        knownCollisions.forEach { (version, identities) ->
            require(entries.filter { it.version == version }.map { it.identity }.toSet() == identities) {
                "Historical Room collision missing from registry: $version"
            }
        }
        val schemas = File("schemas").walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        require(schemas.isNotEmpty()) { "No exported Room schemas found" }
        val found = schemas.map { checkExport(it.name, it.readText(), entries) }.toSet()
        checkRequiredExports(entries, found)
    }

    @Test
    fun unregisteredIdentityAndMismatchedVersionAreRejected() {
        val entries = listOf(Entry(22, "bb4bd6ed0ae5d551d26ef9d695be95b2", true))
        val schema = """{"database":{"version":22,"identityHash":"c15c3d949cdafd3afc602dec4a00e947"}}"""
        assertThrows(IllegalArgumentException::class.java) { checkExport("22.json", schema, entries) }
        assertThrows(IllegalArgumentException::class.java) { checkExport("23.json", schema, entries) }
    }

    @Test
    fun pendingReservationDoesNotAdmitAnExport() {
        val registry = """
            | Version | Identity hash | Branch or source | Commit | Status | Export |
            | --- | --- | --- | --- | --- | --- |
            | 26 | pending | Feature | uncommitted | reserved | no |
        """.trimIndent()
        val entries = registryEntries(registry)
        val schema = """{"database":{"version":26,"identityHash":"c15c3d949cdafd3afc602dec4a00e947"}}"""
        assertThrows(IllegalArgumentException::class.java) { checkExport("26.json", schema, entries) }
    }

    @Test
    fun missingExportsAndNewVersionCollisionsAreRejected() {
        val entries = listOf(Entry(26, "bb4bd6ed0ae5d551d26ef9d695be95b2", true))
        assertThrows(IllegalArgumentException::class.java) { checkRequiredExports(entries, emptySet()) }
        val registry = """
            | Version | Identity hash | Branch or source | Commit | Status | Export |
            | --- | --- | --- | --- | --- | --- |
            | 26 | bb4bd6ed0ae5d551d26ef9d695be95b2 | Feature A | uncommitted | reserved | no |
            | 26 | c15c3d949cdafd3afc602dec4a00e947 | Feature B | uncommitted | reserved | no |
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { registryEntries(registry) }
    }
}
