package io.github.stopcran.kanji.core.content

import kotlinx.serialization.Serializable

const val SUPPORTED_SCHEMA_VERSION = 1

@Serializable
data class Manifest(
    val schemaVersion: Int,
    val contentVersion: String,
    val files: Map<String, String>,
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
)

data class Article(val slug: String, val title: String, val body: String)

@Serializable
data class StrokeData(
    val schemaVersion: Int,
    val kanji: String,
    val viewBox: List<Double>,
    val strokeCount: Int,
    val strokes: List<Stroke>,
)

@Serializable
data class Stroke(val id: Int, val type: String = "", val points: List<List<Double>>)

/** Result of parsing a content snapshot; invalid files are skipped and reported instead of failing the whole sync. */
data class ParsedContent(
    val manifest: Manifest,
    val kanji: List<KanjiCard>,
    val words: List<WordArticle>,
    val articles: List<Article>,
    val strokes: Map<String, StrokeData>,
    val problems: List<String>,
)
