package io.github.stopcran.kanji.core.content

import kotlinx.serialization.Serializable

const val SUPPORTED_SCHEMA_VERSION = 1

@Serializable
data class Manifest(
    val schemaVersion: Int,
    val contentVersion: String,
    val files: Map<String, String>,
    /** Raw-byte SHA-256 of each images/<name>.webp; absent in content that has no images. */
    val images: Map<String, String> = emptyMap(),
)

data class KanjiCard(
    val kanji: String,
    val title: String,
    val jlpt: Int?,
    val tags: List<String>,
    val strokeCount: Int?,
    val radical: String?,
    val radicalNumber: Int?,
    val phonetic: String?,
    val onyomi: List<String>,
    val kunyomi: List<String>,
    val distractors: List<String>,
    val body: String,
)

data class WordArticle(
    val word: String,
    val reading: String,
    val title: String,
    val type: String?,
    val kanji: List<String>,
    val tags: List<String>,
    val body: String,
    val jlpt: Int? = null,
    val quizExclusions: List<String> = emptyList(),
    val quizDistractors: List<String> = emptyList(),
    /** images/<name>.webp path of the scene picture, null when the word has none (or it failed verification). */
    val image: String? = null,
    val imageScene: String? = null,
)

data class Article(val slug: String, val title: String, val body: String)

@Serializable
data class StrokeData(
    val schemaVersion: Int,
    val kanji: String,
    val viewBox: List<Double>,
    val strokeCount: Int,
    val strokes: List<Stroke>,
    /** Alternate accepted drawing orders: each lists all 1-based stroke ids in the order they may be drawn. */
    val orderVariants: List<List<Int>> = emptyList(),
)

@Serializable
data class Stroke(val id: Int, val type: String = "", val points: List<List<Double>>)

/** One counter file (counters/<id>.json): the cards that practise a single counter plus its reverse (noun) questions. */
@Serializable
data class CounterFile(
    val schemaVersion: Int,
    val id: String,
    val counter: String,
    val reading: String,
    val title: String,
    val level: Int = 5,
    val sources: List<String> = emptyList(),
    val cards: List<CounterCard> = emptyList(),
    val reverse: List<CounterReverse> = emptyList(),
    val vocabulary: Map<String, CounterWord> = emptyMap(),
)

@Serializable
data class CounterCard(
    val id: String,
    val kind: String = "plain",
    /** Sentence with exactly one "{}" where the counter goes; any number stays in the text before it. */
    val text: String,
    val en: String,
    val noun: String,
    val phrase: String,
    val reading: String,
    val good: List<String>,
    val kana: List<String> = emptyList(),
    val hard: List<CounterHard> = emptyList(),
    val distractors: List<String>,
    val note: String? = null,
)

@Serializable
data class CounterHard(val forms: List<String>, val phrase: String, val reading: String, val reason: String)

@Serializable
data class CounterReverse(val id: String, val answer: String, val options: List<String>)

@Serializable
data class CounterWord(val reading: String = "", val gloss: String = "")

/** One versioned grammar exercise set stored as grammar/<id>.json. */
@Serializable
data class GrammarSet(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val article: String,
    val constructions: List<GrammarConstruction>,
    val relationships: List<GrammarRelationship>,
    val exercises: List<GrammarExercise>,
)

@Serializable
data class GrammarConstruction(val id: String, val label: String)

@Serializable
data class GrammarRelationship(val id: String, val label: String, val constructions: List<String>)

@Serializable
data class GrammarExercise(
    val id: String,
    val target: String,
    val relationship: String,
    val prompt: String,
    val translation: String,
    val evidence: String,
    val explanation: String,
    val answer: String,
    val acceptedAnswers: List<String>,
    val options: List<GrammarOption>,
)

@Serializable
data class GrammarOption(val id: String, val label: String, val rationale: String)

/** Result of parsing a content snapshot; invalid files are skipped and reported instead of failing the whole sync. */
data class ParsedContent(
    val manifest: Manifest,
    val kanji: List<KanjiCard>,
    val words: List<WordArticle>,
    val articles: List<Article>,
    val strokes: Map<String, StrokeData>,
    val problems: List<String>,
    val counters: List<CounterFile> = emptyList(),
    /** Verified images referenced by a word, keyed by repo path (images/<name>.webp). */
    val images: Map<String, ByteArray> = emptyMap(),
    val grammar: List<GrammarSet> = emptyList(),
)
