package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer
import kotlin.random.Random

@Serializable
data class GrammarChoiceSnapshot(
    val options: List<GrammarOption>,
    val correctOptionIds: Set<String>,
    val schemaVersion: Int = 1,
    val interaction: String = "choose-any",
)

object GrammarChoices {
    private val json = Json { encodeDefaults = true }
    private val id = Regex("[a-z0-9-]+")
    private const val ELIGIBLE_APPEARANCES_PER_MULTI = 5

    fun build(exercise: GrammarExercise, random: Random = Random.Default): GrammarChoiceSnapshot {
        val additional = exercise.additionalCorrectOptions.takeIf {
            it.isNotEmpty() && random.nextInt(ELIGIBLE_APPEARANCES_PER_MULTI) == 0
        }?.random(random)
        return GrammarChoiceSnapshot(
            (exercise.options + listOfNotNull(additional)).shuffled(random),
            setOf(exercise.answer) + listOfNotNull(additional?.id),
        )
    }

    fun encode(snapshot: GrammarChoiceSnapshot): String =
        json.encodeToString(GrammarChoiceSnapshot.serializer(), snapshot)

    fun normalizeAnswer(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)

    fun validateDeclaration(raw: JsonElement?) {
        if (raw == null) return
        require(raw is JsonArray) { "additionalCorrectOptions must be an array, or omitted" }
        raw.forEach { option ->
            require(option is JsonObject && option.keys == setOf("id", "label", "rationale")) {
                "additional correct options need exactly id, label and rationale"
            }
            require(option.values.all { (it as? JsonPrimitive)?.isString == true }) {
                "additional correct option fields must be strings"
            }
        }
    }

    fun validate(exercise: GrammarExercise) {
        val additional = exercise.additionalCorrectOptions
        require(additional.all { it.id.matches(id) && it.label.isNotBlank() && it.rationale.isNotBlank() }) {
            "${exercise.id}: additional options need slug IDs, labels and rationales"
        }
        val options = exercise.options + additional
        require(options.map { it.id }.distinct().size == options.size) {
            "${exercise.id}: additional option IDs must be distinct from every other option"
        }
        val labels = options.map { normalizeAnswer(it.label) }
        require(labels.distinct().size == labels.size) { "${exercise.id}: normalized option labels must be distinct" }
        val accepted = exercise.acceptedAnswers.map(::normalizeAnswer).toSet()
        require(additional.all { normalizeAnswer(it.label) in accepted }) {
            "${exercise.id}: additional correct labels must be accepted typed answers"
        }
        require(exercise.options.filter { it.id != exercise.answer }.none { normalizeAnswer(it.label) in accepted }) {
            "${exercise.id}: distractor labels must not be accepted typed answers"
        }
    }
}
