package io.github.stopcran.kanji.core.content

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads a GitHub archive zip into memory with hard limits (zip-slip, zip bombs, entry count). */
object SafeZip {
    data class Limits(
        val maxEntries: Int = 5_000,
        val maxFileBytes: Long = 2L * 1024 * 1024,
        val maxTotalBytes: Long = 50L * 1024 * 1024,
        /** Everything inflated from the archive, including entries we skip; bounds CPU spent on a hostile zip. */
        val maxInflatedBytes: Long = 100L * 1024 * 1024,
    )

    private val allowedFolders = setOf("kanji", "words", "articles", "strokes")

    /** Returns repo-relative path -> bytes for manifest.json and the content folders; the archive's top-level folder is stripped. */
    fun read(input: InputStream, limits: Limits = Limits()): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        var total = 0L
        var inflated = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > limits.maxEntries) throw ContentException("Archive has too many entries")
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.contains('\\') || name.startsWith("/") || name.split('/').any { it == ".." }) {
                    throw ContentException("Unsafe path in archive: $name")
                }
                val relative = name.substringAfter('/', missingDelimiterValue = "")
                val wanted = relative.isNotEmpty() && isWanted(relative)
                val bytes = readLimited(zip, if (wanted) limits.maxFileBytes else Long.MAX_VALUE, keep = wanted) { inflated += it; if (inflated > limits.maxInflatedBytes) throw ContentException("Archive is too large") }
                if (!wanted) continue
                total += bytes.size
                if (total > limits.maxTotalBytes) throw ContentException("Archive is too large")
                result[relative] = bytes
            }
        }
        return result
    }

    private fun isWanted(relative: String): Boolean {
        if (relative == "manifest.json") return true
        val parts = relative.split('/')
        return parts.size == 2 && parts[0] in allowedFolders && parts[1].isNotEmpty()
    }

    /** Drains the current entry; the bytes are kept only when [keep], and [onBytes] sees every inflated chunk. */
    private fun readLimited(zip: ZipInputStream, max: Long, keep: Boolean, onBytes: (Int) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            onBytes(n)
            size += n
            if (size > max) throw ContentException("File in archive exceeds size limit")
            if (keep) out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
