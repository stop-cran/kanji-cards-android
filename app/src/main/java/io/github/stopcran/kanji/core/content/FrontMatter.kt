package io.github.stopcran.kanji.core.content

/** Minimal front-matter reader: `key: value` scalars and inline `[a, b]` lists. Content is untrusted, so no full YAML. */
object FrontMatter {
    data class Parsed(val fields: Map<String, Any>, val body: String)

    fun parse(text: String): Parsed? {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return null
        val end = normalized.indexOf("\n---", 4)
        if (end < 0) return null
        val header = normalized.substring(4, end)
        val afterMarker = normalized.indexOf('\n', end + 1).let { if (it < 0) normalized.length else it + 1 }
        val body = normalized.substring(afterMarker).trimStart('\n')
        val fields = LinkedHashMap<String, Any>()
        for (line in header.split('\n')) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val key = line.substring(0, colon).trim()
            val raw = line.substring(colon + 1).trim()
            fields[key] = parseValue(raw)
        }
        return Parsed(fields, body)
    }

    private fun parseValue(raw: String): Any {
        if (raw.startsWith("[") && raw.endsWith("]")) {
            val inner = raw.substring(1, raw.length - 1)
            return if (inner.isBlank()) emptyList<String>() else inner.split(',').map { unquote(it.trim()) }
        }
        return unquote(raw)
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) s.substring(1, s.length - 1) else s
}
