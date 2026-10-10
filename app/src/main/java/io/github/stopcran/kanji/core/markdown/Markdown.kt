package io.github.stopcran.kanji.core.markdown

/** Inline run: plain text with style flags, optionally a link target (already resolved by [Links]). */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: LinkTarget? = null)

sealed interface LinkTarget {
    /** Repo-relative path such as `words/大小.md`. */
    data class Doc(val path: String) : LinkTarget
    data class Web(val url: String) : LinkTarget
}

sealed interface Block {
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Paragraph(val spans: List<Span>) : Block
    data class ListItem(val ordinal: Int?, val level: Int, val spans: List<Span>) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : Block
    data class Code(val text: String) : Block
    data object Rule : Block
}

object Links {
    /** Resolves a link written in the document at [fromPath]; returns null for anything the app must not follow. */
    fun resolve(fromPath: String, target: String): LinkTarget? {
        val t = target.trim()
        if (t.startsWith("https://")) return LinkTarget.Web(t)
        if (t.contains("://") || t.startsWith("/") || t.startsWith("#") || t.contains(':') || t.contains('\\')) return null
        val withoutFragment = t.substringBefore('#')
        if (!withoutFragment.endsWith(".md")) return null
        val parts = ArrayDeque(fromPath.split('/').dropLast(1))
        for (seg in withoutFragment.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        val path = parts.joinToString("/")
        val folder = path.substringBefore('/')
        return if (path.count { it == '/' } == 1 && folder in setOf("kanji", "words", "articles")) LinkTarget.Doc(path) else null
    }
}

/** Small Markdown subset used by the content repo: headings, paragraphs, lists, quotes, tables, code fences, rules, emphasis, code, links. No HTML. */
object Markdown {
    fun parse(text: String, path: String): List<Block> = parseLines(text.replace("\r\n", "\n").split('\n'), path, 0)

    private val heading = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    private fun parseLines(lines: List<String>, path: String, depth: Int): List<Block> {
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(inline(para.joinToString(" ") { it.trim() }, path))
            para.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val headingMatch = heading.matchEntire(trimmed)
            val numberedMatch = numbered.matchEntire(line)
            // (indent, text) of a bullet or numbered item
            val item = bullet.matchEntire(line)?.groupValues?.let { it[1] to it[2] } ?: numberedMatch?.groupValues?.let { it[1] to it[3] }
            when {
                trimmed.isEmpty() -> { flush(); i++ }
                trimmed.startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    i++
                    out += Block.Code(code.joinToString("\n"))
                }
                trimmed.length >= 3 && trimmed.all { it == '-' } -> { flush(); out += Block.Rule; i++ }
                headingMatch != null -> {
                    flush()
                    out += Block.Heading(headingMatch.groupValues[1].length, inline(headingMatch.groupValues[2], path))
                    i++
                }
                trimmed.startsWith(">") && depth < MAX_DEPTH -> {
                    flush()
                    val inner = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) inner += lines[i++].trim().removePrefix(">").removePrefix(" ")
                    out += Block.Quote(parseLines(inner, path, depth + 1))
                }
                trimmed.startsWith("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed).map { inline(it, path) }
                    i += 2
                    val rows = mutableListOf<List<List<Span>>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) rows += cells(lines[i++].trim()).map { inline(it, path) }
                    out += Block.Table(header, rows)
                }
                item != null -> {
                    flush()
                    val indent = item.first.length
                    val body = StringBuilder(item.second)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && !bullet.matches(lines[i]) && !numbered.matches(lines[i]) &&
                        !lines[i].trim().startsWith("|") && !lines[i].trim().startsWith(">") && heading.matchEntire(lines[i].trim()) == null
                    ) body.append(' ').append(lines[i++].trim())
                    out += Block.ListItem(numberedMatch?.groupValues?.get(2)?.toIntOrNull(), (indent / 2).coerceAtMost(3), inline(body.toString(), path))
                }
                else -> { para += line; i++ }
            }
        }
        flush()
        return out
    }

    private fun cells(row: String): List<String> {
        val r = row.trim().removePrefix("|").removeSuffix("|")
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < r.length) {
            val c = r[k]
            if (c == '\\' && k + 1 < r.length && r[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (c == '|') { result += cur.toString().trim(); cur.clear() } else cur.append(c)
            k++
        }
        result += cur.toString().trim()
        return result
    }

    fun inline(text: String, path: String): List<Span> {
        val out = mutableListOf<Span>()
        parseInline(text, path, bold = false, italic = false, out, 0)
        return out.fold(mutableListOf()) { acc, s ->
            val last = acc.lastOrNull()
            if (last != null && last.link == null && s.link == null && last.bold == s.bold && last.italic == s.italic && last.code == s.code) {
                acc[acc.lastIndex] = last.copy(text = last.text + s.text)
            } else acc += s
            acc
        }
    }

    private fun parseInline(s: String, path: String, bold: Boolean, italic: Boolean, out: MutableList<Span>, depth: Int) {
        val buf = StringBuilder()
        fun flushText() {
            if (buf.isNotEmpty()) out += Span(buf.toString(), bold, italic)
            buf.clear()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|>" -> { buf.append(s[i + 1]); i += 2 }
                depth < MAX_DEPTH && s.startsWith("**", i) -> {
                    val end = s.indexOf("**", i + 2)
                    if (end > i + 2) { flushText(); parseInline(s.substring(i + 2, end), path, true, italic, out, depth + 1); i = end + 2 } else { buf.append(c); i++ }
                }
                depth < MAX_DEPTH && c == '*' && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                    var end = i + 1
                    while (end < s.length && !(s[end] == '*' && !s[end - 1].isWhitespace() && !s.startsWith("**", end))) end++
                    if (end < s.length) { flushText(); parseInline(s.substring(i + 1, end), path, bold, true, out, depth + 1); i = end + 1 } else { buf.append(c); i++ }
                }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) { flushText(); out += Span(s.substring(i + 1, end), code = true); i = end + 1 } else { buf.append(c); i++ }
                }
                depth < MAX_DEPTH && c == '[' -> {
                    val close = findClosingBracket(s, i)
                    if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                        val end = s.indexOf(')', close + 2)
                        if (end > 0) {
                            flushText()
                            val label = s.substring(i + 1, close)
                            val target = Links.resolve(path, s.substring(close + 2, end))
                            val inner = mutableListOf<Span>()
                            parseInline(label, path, bold, italic, inner, depth + 1)
                            if (target == null) out += inner else out += inner.map { it.copy(link = target) }
                            i = end + 1
                            continue
                        }
                    }
                    buf.append(c); i++
                }
                else -> { buf.append(c); i++ }
            }
        }
        flushText()
    }

    private fun findClosingBracket(s: String, open: Int): Int {
        var depth = 0
        for (k in open until minOf(s.length, open + MAX_LABEL)) {
            when (s[k]) {
                '[' -> depth++
                ']' -> if (--depth == 0) return k
            }
        }
        return -1
    }

    private const val MAX_DEPTH = 8
    private const val MAX_LABEL = 2_000
}
