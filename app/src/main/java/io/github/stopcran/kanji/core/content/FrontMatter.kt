package io.github.stopcran.kanji.core.content

/** Minimal front-matter reader: `key: value` scalars and inline `[a, b]` lists. Content is untrusted, so no full YAML. */
object FrontMatter {
    data class Entry(val key: String, val rawValue: String, val indented: Boolean)
    data class Parsed(val fields: Map<String, Any>, val body: String, val entries: List<Entry>)
    private val plainKey = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    fun parse(text: String, requirePlainKeys: Boolean = false): Parsed? {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return null
        // Search from the newline that ends the opening delimiter so an empty header (`---\n---`) is recognised.
        var end = normalized.indexOf("\n---", 3)
        if (!requirePlainKeys) {
            while (end >= 0) {
                val lineEnd = normalized.indexOf('\n', end + 1).let { if (it < 0) normalized.length else it }
                if (normalized.substring(end + 4, lineEnd).isBlank()) break
                end = normalized.indexOf("\n---", end + 1)
            }
        }
        if (end < 0) return null
        val header = if (end <= 4) "" else normalized.substring(4, end)
        val markerEnd = normalized.indexOf('\n', end + 1).let { if (it < 0) normalized.length else it }
        if (requirePlainKeys) {
            // A delimiter prefix must not conceal unsupported keys from word-header validation.
            val suffix = normalized.substring(end + 4, markerEnd)
            if (suffix.isNotBlank() && (!suffix.first().isWhitespace() || !suffix.trimStart().startsWith("#"))) {
                throw ContentException("word front matter must end with a standalone '---' delimiter")
            }
        }
        val afterMarker = if (markerEnd < normalized.length) markerEnd + 1 else markerEnd
        val body = normalized.substring(afterMarker).trimStart('\n')
        val entries = mutableListOf<Entry>()
        for (line in header.split('\n')) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val colon = line.indexOf(':')
            if (requirePlainKeys && (colon <= 0 || !plainKey.matches(line.substring(0, colon).trimEnd()))) {
                throw ContentException("word header keys must be unquoted, unindented ASCII identifiers matching [A-Za-z_][A-Za-z0-9_-]*")
            }
            if (colon <= 0) return null
            val key = line.substring(0, colon).trim()
            val raw = line.substring(colon + 1).trim()
            entries += Entry(key, raw, line.first().isWhitespace())
        }
        val fields = LinkedHashMap<String, Any>()
        for (entry in entries) fields[entry.key] = parseValue(entry.rawValue)
        return Parsed(fields, body, entries)
    }

    private fun parseValue(raw: String): Any {
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return listItems(raw)?.map(::unquote) ?: raw
        }
        return unquote(raw)
    }

    /** Strict string lists do not coerce YAML scalars or accept syntax this flat reader cannot interpret. */
    fun parseStringList(raw: String): List<String>? {
        if (!raw.startsWith("[") || !raw.endsWith("]")) return null
        return (listItems(raw) ?: return null).map { item ->
            val quoted = item.length >= 2 && (item.first() == '\'' && item.last() == '\'' || item.first() == '"' && item.last() == '"')
            if (quoted) {
                val value = unquote(item)
                if (item.first() in value || '\\' in value) return null
                value
            } else {
                if (item.isEmpty() || item.any { it in "[]{}'\",:&*!#?|>@`\\" } || nonStringScalar.matches(item)) return null
                item
            }
        }
    }

    /** Splits on commas outside quotes; null when a quote is unterminated or text follows a closing quote. */
    private fun listItems(raw: String): List<String>? {
        val inner = raw.substring(1, raw.length - 1)
        if (inner.isBlank()) return emptyList()
        val items = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var closed = false
        for (c in inner) {
            when {
                quote != null -> {
                    cur.append(c)
                    if (c == quote) { quote = null; closed = true }
                }
                c == ',' -> { items += cur.toString().trim(); cur.clear(); closed = false }
                (c == '"' || c == '\'') && cur.isBlank() && !closed -> { quote = c; cur.append(c) }
                closed && !c.isWhitespace() -> return null
                else -> cur.append(c)
            }
        }
        if (quote != null) return null
        items += cur.toString().trim()
        return items
    }

    private val nonStringScalar = Regex(
        "(?i:~|null|true|false|yes|no|on|off|[-+]?(?:[0-9][0-9_]*(?:\\.[0-9_]*)?|\\.[0-9_]+)(?:e[-+]?[0-9]+)?|" +
            "[-+]?0(?:x[0-9a-f_]+|o[0-7_]+|b[01_]+)|[-+]?\\.(?:inf|nan)|[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}(?:[Tt ].*)?)",
    )

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) s.substring(1, s.length - 1) else s
}
