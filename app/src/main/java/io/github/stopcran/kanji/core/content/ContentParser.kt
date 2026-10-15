package io.github.stopcran.kanji.core.content

import kotlinx.serialization.json.Json
import java.security.MessageDigest

class ContentException(message: String) : Exception(message)

/** Turns a map of repo-relative path -> bytes into validated content. Everything here is treated as untrusted input. */
object ContentParser {
    const val MAX_QUIZ_DISTRACTORS = 3
    const val MAX_IMAGE_BYTES = 150 * 1024
    private val IMAGE_PATH = Regex("images/[^/\\\\\\s]+\\.webp")

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
        val counters = mutableListOf<CounterFile>()
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
                    path.startsWith("counters/") && path.endsWith(".json") -> counters += parseCounter(path, bytes.decodeToString())
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
        val images = verifiedImages(manifest, files, words, problems)
        return ParsedContent(manifest, unique, words, articles, strokes, problems, resolveCounters(counters, problems), images)
    }

    /**
     * Images are untrusted binary input: raw-byte hash, WebP container check and one owner word each. A word whose image
     * fails any check keeps its text and loses the picture; the sync never fails because of an image.
     */
    private fun verifiedImages(
        manifest: Manifest,
        files: Map<String, ByteArray>,
        words: MutableList<WordArticle>,
        problems: MutableList<String>,
    ): Map<String, ByteArray> {
        val good = HashMap<String, ByteArray>()
        for ((path, expectedHash) in manifest.images.toSortedMap()) {
            val bytes = files[path]
            val reason = imageProblem(path, bytes, expectedHash)
            if (reason == null) good[path] = bytes!! else problems += "$path: $reason"
        }
        val owners = HashMap<String, String>()
        for (i in words.indices) {
            val w = words[i]
            val image = w.image ?: continue
            val reason = when {
                image !in good -> "image '$image' is missing or failed verification"
                owners.putIfAbsent(image, w.word) != null -> "image '$image' is already used by ${owners[image]}"
                else -> null
            }
            if (reason != null) {
                problems += "words/${w.word}.md: $reason"
                words[i] = w.copy(image = null, imageScene = null)
            }
        }
        return good.filterKeys { it in owners }
    }

    private fun imageProblem(path: String, bytes: ByteArray?, expectedHash: String): String? = when {
        !IMAGE_PATH.matches(path) -> "not a flat images/<name>.webp path"
        bytes == null -> "listed in manifest but missing"
        bytes.size > MAX_IMAGE_BYTES -> "larger than $MAX_IMAGE_BYTES bytes"
        !isWebp(bytes) -> "not a WebP file"
        rawSha256(bytes) != expectedHash -> "hash mismatch"
        else -> null
    }

    private fun isWebp(bytes: ByteArray) = bytes.size > 12 &&
        String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" &&
        riffSize(bytes) + 8 == bytes.size.toLong()

    private fun riffSize(b: ByteArray) =
        (0..3).sumOf { (b[4 + it].toLong() and 0xFF) shl (8 * it) }

    /** Card IDs are the SRS keys, so a duplicate anywhere drops the later file; reverse cards need all four options. */
    private fun resolveCounters(files: List<CounterFile>, problems: MutableList<String>): List<CounterFile> {
        val seen = HashSet<String>()
        val kept = files.sortedBy { it.id }.filter { f ->
            val ids = f.cards.map { it.id } + f.reverse.map { it.id }
            val clash = ids.firstOrNull { it in seen }
            if (clash != null) problems += "counters/${f.id}.json: duplicate card ID '$clash'"
            else seen += ids
            clash == null
        }
        val cardIds = kept.flatMapTo(hashSetOf()) { f -> f.cards.map { it.id } }
        return kept.map { f ->
            val ok = f.reverse.filter { r ->
                val missing = r.options.filter { it !in cardIds }
                if (missing.isNotEmpty()) problems += "counters/${f.id}.json: reverse '${r.id}' references missing cards: ${missing.joinToString(", ")}"
                missing.isEmpty()
            }
            if (ok.size == f.reverse.size) f else f.copy(reverse = ok)
        }
    }

    fun parseCounter(path: String, text: String): CounterFile {
        val f = json.decodeFromString(CounterFile.serializer(), text)
        require(f.schemaVersion == 1) { "unsupported counters schema ${f.schemaVersion}" }
        require(f.id == path.substringAfterLast('/').removeSuffix(".json")) { "'id' must match the file name" }
        require(f.counter.isNotBlank() && f.cards.isNotEmpty()) { "missing counter or cards" }
        for (c in f.cards) {
            require(c.id.isNotBlank() && c.text.split("{}").size == 2) { "card '${c.id}' needs exactly one {} blank" }
            require(c.good.isNotEmpty() && c.reading.isNotBlank() && c.noun.isNotBlank()) { "card '${c.id}' is incomplete" }
            require(c.distractors.size == MAX_QUIZ_DISTRACTORS && c.distractors.toSet().size == MAX_QUIZ_DISTRACTORS && f.counter !in c.distractors) {
                "card '${c.id}' needs $MAX_QUIZ_DISTRACTORS distinct distractors other than the counter"
            }
        }
        require(f.cards.map { it.id }.toSet().size == f.cards.size) { "duplicate card IDs" }
        for (r in f.reverse) {
            require(r.options.size == 4 && r.options.toSet().size == 4 && r.answer in r.options) {
                "reverse '${r.id}' needs 4 distinct options including the answer"
            }
        }
        return f
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
        // A malformed picture reference costs the word its picture only; the validator reports it to the author.
        val image = f.str("image")?.takeIf { IMAGE_PATH.matches(it) && f.str("image_scene") != null }
        val scene = f.str("image_scene")
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
            image = image,
            imageScene = if (image != null) scene else null,
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

    /** Text hashes are computed over LF-normalised bytes, matching tools/build_manifest.py; images use [rawSha256]. */
    fun sha256(bytes: ByteArray): String {
        val normalized = String(bytes, Charsets.ISO_8859_1).replace("\r\n", "\n").toByteArray(Charsets.ISO_8859_1)
        return rawSha256(normalized)
    }

    fun rawSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
