package io.github.stopcran.kanji.core.content

import kotlinx.serialization.json.JsonObject

enum class ContentType { Kanji, Words, Grammar, Counters, Listening, Other }

data class ContentRejections(
    val files: Map<ContentType, Int> = emptyMap(),
    val counterQuestions: Int = 0,
)

/** Count rejected documents, not diagnostic messages: optional metadata warnings keep their owning cards. */
fun ParsedContent.rejectedContent(additionalListening: Set<String> = emptySet()): ContentRejections {
    val accepted = buildSet {
        addAll(kanji.map { "kanji/${it.kanji}.md" })
        addAll(words.map { "words/${it.word}.md" })
        addAll(articles.map { "articles/${it.slug}.md" })
        addAll(strokes.keys.map { "strokes/$it.json" })
        addAll(counters.map { "counters/${it.id}.json" })
        addAll(grammar.map { "grammar/${it.id}.json" })
        addAll(listening.map { "listening/${it.passage.id}.json" })
        addAll(listeningTasks.records.map { "listening-tasks/${it.task.id}.json" })
    }
    val rejected = (manifest.files.keys - accepted).groupBy { path ->
        when (path.substringBefore('/')) {
            "kanji" -> ContentType.Kanji
            "words" -> ContentType.Words
            "grammar" -> ContentType.Grammar
            "counters" -> ContentType.Counters
            "listening", "listening-tasks" -> ContentType.Listening
            else -> ContentType.Other
        }
    }.mapValues { it.value.toMutableSet() }.toMutableMap()
    val taskPaths = (manifest.listeningTasks as? JsonObject)?.keys.orEmpty()
    rejected.getOrPut(ContentType.Listening) { mutableSetOf() }.addAll(
        (manifest.listening.keys - accepted) + (taskPaths - accepted) + additionalListening,
    )
    return ContentRejections(rejected.mapValues { it.value.size }.filterValues { it > 0 }, rejectedCounterQuestions)
}
