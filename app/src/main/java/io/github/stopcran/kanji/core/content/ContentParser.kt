package io.github.stopcran.kanji.core.content

import kotlinx.serialization.json.Json
import java.security.MessageDigest

class ContentException(message: String) : Exception(message)

/** Turns a map of repo-relative path -> bytes into validated content. Everything here is treated as untrusted input. */
object ContentParser {
    const val MAX_QUIZ_DISTRACTORS = 3

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(files: Map<String, ByteArray>): ParsedContent {
        val manifestBytes = files["manifest.json"] ?: throw ContentException("manifest.json is missing")
        val manifest = try {
            json.decodeFromString(Manifest.serializer(), manifestBytes.decodeToString())
        } catch (e: Exception) {
            throw ContentException("manifest.json is invalid: ${e.message}")
        }
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw ContentException("Unsupported content schema ${manifest.schemaVersion}; this app supports $SUPPORTED_SCHEMA_VERSION")
        }

        val problems = mutableListOf<String>()
        val kanji = mutableListOf<KanjiCard>()
        val words = mutableListOf<WordArticle>()
        val articles = mutableListOf<Article>()
        val strokes = mutableMapOf<String, StrokeData>()

        for ((path, expectedHash) in manifest.files.toSortedMap()) {
            if (!isSafePath(path)) {
                problems += "$path: unsafe path"
                continue
            }
            val bytes = files[path]
            if (bytes == null) {
                problems += "$path: listed in manifest but missing"
                continue
            }
            if (sha256(bytes) != expectedHash) {
                problems += "$path: hash mismatch"
                continue
            }
            try {
                when {
                    path.startsWith("kanji/") && path.endsWith(".md") -> kanji += parseKanji(path, bytes.decodeToString())
                    path.startsWith("words/") && path.endsWith(".md") -> words += parseWord(path, bytes.decodeToString())
                    path.startsWith("articles/") && path.endsWith(".md") -> articles += parseArticle(path, bytes.decodeToString())
                    path.startsWith("strokes/") && path.endsWith(".json") -> {
                        val data = json.decodeFromString(StrokeData.serializer(), bytes.decodeToString())
                        require(data.schemaVersion == 1) { "unsupported strokes schema ${data.schemaVersion}" }
                        require(data.strokes.size == data.strokeCount) { "stroke count mismatch" }
                        require(data.strokes.all { it.points.size >= 2 && it.points.all { p -> p.size == 2 } }) { "bad stroke points" }
                        strokes[data.kanji] = data
                    }
                }
            } catch (e: Exception) {
                problems += "$path: ${e.message}"
            }
        }

        // A rejected word can make another word's references dangling; do not persist references to skipped files.
        while (true) {
            val wordIds = words.mapTo(hashSetOf()) { it.word }
            val removed = words.removeAll { word ->
                val missing = word.quizExclusions.filter { it !in wordIds }
                if (missing.isEmpty()) false else {
                    problems += "words/${word.word}.md: 'quiz_exclusions' references missing words: ${missing.joinToString(", ")}"
                    true
                }
            }
            if (!removed) break
        }

        // Missing curated distractors are dropped from the word (the word itself stays usable).
        val knownWords = words.mapTo(hashSetOf()) { it.word }
        for (i in words.indices) {
            val w = words[i]
            val kept = w.quizDistractors.filter { it in knownWords }
            if (kept.size != w.quizDistractors.size) {
                problems += "words/${w.word}.md: 'quiz_distractors' references missing words: ${(w.quizDistractors - kept.toSet()).joinToString(", ")}"
                words[i] = w.copy(quizDistractors = kept)
            }
        }

        val titles = HashSet<String>()
        val unique = kanji.filter { c ->
            titles.add(c.title).also { if (!it) problems += "kanji/${c.kanji}.md: duplicate title '${c.title}'" }
        }
        return ParsedContent(manifest, unique, words, articles, strokes, problems)
    }

    fun parseKanji(path: String, text: String): KanjiCard {
        val fm = FrontMatter.parse(text) ?: throw ContentException("missing front matter")
        val f = fm.fields
        val kanji = f.str("kanji") ?: throw ContentException("missing 'kanji'")
        val expected = path.substringAfterLast('/').removeSuffix(".md")
        if (kanji != expected || kanji.codePointCount(0, kanji.length) != 1) {
            throw ContentException("'kanji' must be the single character in the file name")
        }
        return KanjiCard(
            kanji = kanji,
            title = f.str("title") ?: throw ContentException("missing 'title'"),
            jlpt = f.str("jlpt")?.toIntOrNull(),
            tags = f.list("tags"),
            strokeCount = f.str("strokes")?.toIntOrNull(),
            radical = f.str("radical"),
            radicalNumber = f.str("radicalNumber")?.toIntOrNull(),
            phonetic = f.str("phonetic"),
            onyomi = f.list("onyomi"),
            kunyomi = f.list("kunyomi"),
            distractors = f.list("distractors"),
            body = fm.body,
        )
    }

    fun parseWord(path: String, text: String): WordArticle {
        val fm = FrontMatter.parse(text, requirePlainKeys = true) ?: throw ContentException("missing front matter")
        val f = fm.fields
        val word = f.str("word") ?: throw ContentException("missing 'word'")
        if (word != path.substringAfterLast('/').removeSuffix(".md")) throw ContentException("'word' must match the file name")
        return WordArticle(
            word = word,
            reading = f.str("reading") ?: throw ContentException("missing 'reading'"),
            title = f.str("title") ?: throw ContentException("missing 'title'"),
            type = f.str("type"),
            kanji = f.list("kanji"),
            tags = f.list("tags"),
            body = fm.body,
            jlpt = fm.wordJlpt(),
            quizExclusions = fm.wordIdList("quiz_exclusions", word),
            quizDistractors = fm.wordIdList("quiz_distractors", word).also {
                if (it.size > MAX_QUIZ_DISTRACTORS) throw ContentException("'quiz_distractors' may list at most $MAX_QUIZ_DISTRACTORS words")
            },
        )
    }

    fun parseArticle(path: String, text: String): Article {
        val fm = FrontMatter.parse(text)
        val body = fm?.body ?: text.removePrefix("\uFEFF").replace("\r\n", "\n")
        val slug = path.substringAfterLast('/').removeSuffix(".md")
        val title = fm?.fields?.str("title")
            ?: body.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: slug
        return Article(slug, title, body)
    }

    /** Hashes are computed over LF-normalised bytes, matching tools/build_manifest.py. */
    fun sha256(bytes: ByteArray): String {
        val normalized = String(bytes, Charsets.ISO_8859_1).replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1)
        return MessageDigest.getInstance("SHA-256").digest(normalized).joinToString("") { "%02x".format(it) }
    }

    private fun isSafePath(path: String) =
        !path.startsWith("/") && !path.contains('\\') && path.split('/').none { it == ".." || it.isEmpty() }

    private fun Map<String, Any>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun FrontMatter.Parsed.wordJlpt(): Int? {
        val expected = "a single top-level unquoted integer from 1 to 5"
        val field = wordDeclaration("jlpt", expected) ?: return null
        if (!field.rawValue.matches(Regex("[1-5]"))) {
            throw ContentException("'jlpt' must be $expected")
        }
        return field.rawValue.toInt()
    }

    private fun FrontMatter.Parsed.wordIdList(key: String, word: String): List<String> {
        val field = wordDeclaration(key, "a single top-level unquoted-key inline list") ?: return emptyList()
        val ids = FrontMatter.parseStringList(field.rawValue) ?: throw ContentException("'$key' must be an inline list of strings")
        val problem = when {
            ids.any { it.isBlank() || it.any { c -> c.isISOControl() || c == '/' || c == '\\' } } -> "contains an empty or invalid word ID"
            ids.size != ids.toSet().size -> "contains duplicate IDs"
            word in ids -> "must not contain the word itself"
            else -> null
        }
        if (problem != null) throw ContentException("'$key' $problem")
        return ids
    }

    private fun FrontMatter.Parsed.wordDeclaration(key: String, expected: String): FrontMatter.Entry? {
        // Word-header syntax is already validated; retain every entry so duplicate canonical keys cannot be hidden.
        val declarations = entries.filter { it.key == key }
        if (declarations.isEmpty()) return null
        return declarations.singleOrNull() ?: throw ContentException("'$key' must be $expected")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.list(key: String): List<String> = when (val v = this[key]) {
        null -> emptyList()
        is List<*> -> v as List<String>
        is String -> if (v.isEmpty()) emptyList() else throw ContentException("'$key' must be an inline list like [a, b]")
        else -> emptyList()
    }
}
