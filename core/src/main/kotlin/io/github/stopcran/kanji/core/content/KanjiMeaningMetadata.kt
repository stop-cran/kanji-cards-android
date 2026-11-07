package io.github.stopcran.kanji.core.content

/** Strict only for meaning exclusions: legacy kanji headers keep their existing interpretation. */
internal object KanjiMeaningMetadata {
    private const val KEY = "quiz_exclusions"
    private val decoration = Regex("^(?:![^\\s]+|&[^\\s]+|\\?)\\s+")
    private val verbatimTag = Regex("!<[^>]*>")
    private val anchor = Regex("(?:^|\\s)&([^\\s]+)\\s+")
    private val inlineComment = Regex("\\s+#.*$")
    private val quotedScalar = Regex(
        """^((?:(?:![^\s]+|&[^\s]+)\s+)*(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'))(?:\s*#.*)?$""",
    )
    private val escape = Regex("""\\(?:x([0-9a-fA-F]{2})|u([0-9a-fA-F]{4})|U([0-9a-fA-F]{8}))""")

    fun parse(fm: FrontMatter.Parsed, kanji: String): List<String> {
        val aliases = aliases(fm)
        val declarations = fm.entries.filter {
            val key = canonical(it.rawLine.replace(verbatimTag, "!tag").substringBefore(':').trim())
            key == KEY || key.startsWith("*") && key.removePrefix("*") in aliases
        }
        if (declarations.isEmpty()) return emptyList()
        val declaration = declarations.singleOrNull()?.takeIf { it.key == KEY && !it.indented }
        val ids = declaration?.let { FrontMatter.parseStringList(it.rawValue) }
            ?: throw ContentException("'$KEY' must be a single top-level unquoted key with an inline list of strings")
        if (!ids.all(::validId) || ids.distinct().size != ids.size || kanji in ids) {
            throw ContentException("'$KEY' must contain distinct single-character kanji IDs, not the card itself")
        }
        return ids
    }

    private fun aliases(fm: FrontMatter.Parsed): Set<String> {
        val dependents = mutableMapOf<String, MutableList<String>>()
        for (entry in fm.entries) {
            val raw = entry.rawValue.replace(verbatimTag, "!tag")
            val value = quotedScalar.matchEntire(raw)?.groupValues?.get(1) ?: raw.replace(inlineComment, "")
            val name = anchor.find(value)?.groupValues?.get(1) ?: continue
            dependents.getOrPut(canonical(value)) { mutableListOf() } += name
        }
        val aliases = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(KEY))
        while (pending.isNotEmpty()) {
            for (name in dependents[pending.removeFirst()].orEmpty()) {
                if (aliases.add(name)) pending.addLast("*$name")
            }
        }
        return aliases
    }

    private fun canonical(key: String): String {
        var plain = key
        while (decoration.containsMatchIn(plain)) plain = plain.replaceFirst(decoration, "")
        plain = plain.trim('\'', '"')
        return escape.replace(plain) { match ->
            val code = match.groupValues.drop(1).first { it.isNotEmpty() }.toLong(16)
            if (code <= Character.MAX_CODE_POINT) String(Character.toChars(code.toInt())) else match.value
        }
    }

    private fun validId(id: String): Boolean {
        if (id.codePointCount(0, id.length) != 1) return false
        val code = id.codePointAt(0)
        if (code in 0xD800..0xDFFF || code in 0xFDD0..0xFDEF || (code and 0xFFFF) in setOf(0xFFFE, 0xFFFF)) return false
        return !Character.isISOControl(code) && !Character.isWhitespace(code) && !Character.isSpaceChar(code) && id !in listOf("/", "\\")
    }

    fun resolveReferences(cards: MutableList<KanjiCard>, problems: MutableList<String>) {
        while (true) {
            val accepted = cards.mapTo(hashSetOf()) { it.kanji }
            val removed = cards.removeAll { card ->
                val missing = card.quizExclusions.filter { it !in accepted }
                if (missing.isEmpty()) false else {
                    problems += "kanji/${card.kanji}.md: '$KEY' references missing kanji: ${missing.joinToString(", ")}"
                    true
                }
            }
            if (!removed) return
        }
    }
}
