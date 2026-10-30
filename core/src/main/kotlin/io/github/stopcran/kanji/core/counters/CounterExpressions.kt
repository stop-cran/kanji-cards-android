package io.github.stopcran.kanji.core.counters

import io.github.stopcran.kanji.core.content.CounterExpression
import io.github.stopcran.kanji.core.content.CounterFile
import io.github.stopcran.kanji.core.reading.ReadingKey
import java.text.Normalizer

enum class CounterGroup(val code: String, val label: String) {
    All("all", "All"),
    Everyday("everyday", "Everyday counts"),
    Time("time", "Time & dates");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: All
        fun fromRoute(route: String) = fromCode(route.split(':').getOrNull(3))
    }
}

object CounterExpressions {
    const val PARSER_EPOCH = 2
    private val kana = Regex("[ぁ-ゟァ-ヿー]+")
    private val id = Regex("[a-z]+-[a-z]+-\\d+")

    fun writtenKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).filterNot { it.isWhitespace() }

    fun readingKey(value: String): String = ReadingKey.fold(writtenKey(value))

    fun canRead(written: String): Boolean = !kana.matches(writtenKey(written))

    fun grade(input: String, expression: CounterExpression, reading: Boolean): CounterResult {
        val accepted = if (reading) expression.readingAnswers else expression.writtenAnswers
        val key = if (reading) ::readingKey else ::writtenKey
        return CounterResult(if (accepted.any { key(it) == key(input) }) CounterVerdict.Good else CounterVerdict.Wrong)
    }

    fun validate(file: CounterFile) {
        require(file.group in listOf("everyday", "time")) { "unknown counter group '${file.group}'" }
        require(file.counter.isNotBlank() && file.title.isNotBlank() && file.level in 1..5) { "incomplete counter file" }
        require(kana.matches(file.reading) && file.sources.all { it.startsWith("https://") }) { "invalid counter reading or sources" }
        require(file.article?.matches(Regex("articles/[a-z0-9-]+\\.md")) == true) { "expression file needs an article" }
        require(file.cards.isEmpty() && file.reverse.isEmpty() && file.expressions.size in 1..256) {
            "v2 needs 1-256 expressions, not suffix or reverse cards"
        }
        file.expressions.forEach { e ->
            require(id.matches(e.id)) { "invalid expression ID '${e.id}'" }
            require(listOf(e.cue, e.written, e.explanation).all { it.isNotBlank() && it.length <= 1000 }) {
                "expression '${e.id}' has missing or oversized text"
            }
            require(e.written in e.writtenAnswers && e.reading in e.readingAnswers) { "missing canonical answer in '${e.id}'" }
            require(e.writtenAnswers.size in 1..16 && e.writtenAnswers.all { it.isNotBlank() && it.length <= 80 }) {
                "invalid written answers in '${e.id}'"
            }
            require(e.writtenAnswers.distinctBy(::writtenKey).size == e.writtenAnswers.size) { "duplicate written answers in '${e.id}'" }
            validateReadings(e.id, e.reading, e.readingAnswers)
        }
        require(file.expressions.distinctBy { it.id }.size == file.expressions.size) { "duplicate expression IDs" }
    }

    fun validateReadings(id: String, canonical: String, answers: List<String>) {
        require(answers.size in 1..8 && canonical in answers && answers.all { it.length <= 80 && kana.matches(it) }) {
            "invalid canonical-expression readings in '$id'"
        }
        require(answers.distinctBy(::readingKey).size == answers.size) { "duplicate reading answers in '$id'" }
    }
}
