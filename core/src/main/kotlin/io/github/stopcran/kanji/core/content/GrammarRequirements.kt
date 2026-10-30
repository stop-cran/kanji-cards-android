package io.github.stopcran.kanji.core.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** New readers retain these leaf gates; legacy readers ignore this additive set-level field. */
object GrammarRequirements {
    const val PARSER_EPOCH = 6

    fun apply(value: JsonElement?, groups: List<GrammarIntroductionGroup>): List<GrammarIntroductionGroup> {
        if (value == null) return groups
        val policy = value as? JsonObject
        require(policy != null && policy.keys == setOf("schemaVersion", "requires")) { "invalid foundationRequirements fields" }
        val version = policy["schemaVersion"] as? JsonPrimitive
        require(version != null && !version.isString && version.content == "1") { "unsupported foundationRequirements schema" }
        val requirements = policy["requires"] as? JsonObject
        require(requirements != null) { "foundationRequirements.requires must be an object" }
        require(requirements.keys.all { key -> groups.any { it.id == key } }) { "foundationRequirements must name local authored groups" }
        return groups.map { group ->
            val raw = requirements[group.id] ?: return@map group
            require(group.requires.isEmpty()) { "${group.id}: do not mix legacy requires with foundationRequirements" }
            val parents = raw as? JsonArray
            require(parents != null && parents.all { it is JsonPrimitive && it.isString }) { "${group.id}: prerequisites need group-ID strings" }
            group.copy(requires = parents.map { (it as JsonPrimitive).content })
        }
    }
}
