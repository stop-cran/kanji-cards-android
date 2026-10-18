package io.github.stopcran.kanji.core.content

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Strict new objects inside the otherwise backward-compatible Grammar format. */
object GrammarGroups {
    private val id = Regex("[a-z0-9-]+")
    private val importance = Regex("[1-3]")

    fun decode(value: JsonElement?): List<GrammarIntroductionGroup> {
        if (value == null) return emptyList()
        value.jsonArray.forEach { element ->
            val group = element.jsonObject
            strings(group, "id", "label")
            group.getValue("facets").jsonArray.forEach { strings(it.jsonObject, "id", "label") }
            group.getValue("members").jsonArray.forEach { strings(it.jsonObject, "exerciseId", "facetId", "exampleKey") }
            group["requires"]?.jsonArray?.forEach { require(it is JsonPrimitive && it.isString) { "requires needs group-ID strings" } }
            group["next"]?.jsonArray?.forEach {
                val link = it.jsonObject
                strings(link, "groupId", "reason")
                val weight = link["importance"] as? JsonPrimitive
                require(weight != null && !weight.isString && importance.matches(weight.content)) {
                    "recommendation importance must be an integer from 1 to 3"
                }
            }
        }
        return Json.decodeFromJsonElement(ListSerializer(GrammarIntroductionGroup.serializer()), value)
    }

    private fun strings(value: JsonObject, vararg keys: String) {
        keys.forEach { key ->
            val item = value[key] as? JsonPrimitive
            require(item != null && item.isString && item.content.isNotBlank()) { "$key must be a nonblank string" }
        }
    }

    fun validate(set: GrammarSet) {
        val groups = set.introductionGroups
        require(groups.map { it.id }.distinct().size == groups.size) { "duplicate introduction group ID" }
        val owned = hashSetOf<String>()
        val exercises = set.exercises.associateBy { it.id }
        groups.forEach { group ->
            require(id.matches(group.id)) { "invalid introduction group ID" }
            require(group.facets.isNotEmpty() && group.members.isNotEmpty()) { "${group.id}: facets and members must not be empty" }
            val facets = group.facets.map { it.id }
            require(facets.all(id::matches) && facets.distinct().size == facets.size) { "${group.id}: invalid or duplicate facets" }
            group.members.forEach { member ->
                require(listOf(member.exerciseId, member.facetId, member.exampleKey).all(id::matches)) { "${group.id}: invalid member ID" }
                require(member.exerciseId in exercises && member.facetId in facets) { "${group.id}: member needs a local exercise and facet" }
                require(owned.add(member.exerciseId)) { "${group.id}: duplicate exercise ownership" }
            }
            facets.forEach { facet ->
                val members = group.members.filter { it.facetId == facet }
                require(members.map { it.exampleKey }.distinct().size >= 2) { "${group.id}/$facet: needs two distinct example keys" }
                require(members.map { exercises.getValue(it.exerciseId).target }.distinct().size == 1) {
                    "${group.id}/$facet: members must target one answer class"
                }
            }
            references(group.id, group.requires)
            references(group.id, group.next.map { it.groupId })
        }
    }

    private fun references(owner: String, targets: List<String>) {
        require(targets.all { id.matches(it) && it != owner } && targets.distinct().size == targets.size) {
            "$owner: invalid, self or duplicate group reference"
        }
    }

    data class Resolution(val sets: List<GrammarSet>, val errors: List<String>, val warnings: List<String>)

    fun resolve(sets: List<GrammarSet>): Resolution {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val duplicated = sets.flatMap { set -> set.introductionGroups.map { it.id to set.id } }
            .groupBy { it.first }.filterValues { it.size > 1 }.values.flatten().map { it.second }.toSet()
        var kept = sets.filter { set ->
            (set.id !in duplicated).also { if (!it) errors += "grammar/${set.id}.json: duplicate source-global group ID" }
        }
        while (true) {
            val groups = kept.flatMap { it.introductionGroups }.associateBy { it.id }
            val invalid = invalidPrerequisites(groups)
            val next = kept.filter { set ->
                val rejected = set.introductionGroups.filter { it.id in invalid }
                if (rejected.isNotEmpty()) {
                    errors += "grammar/${set.id}.json: missing/cyclic prerequisite chain in ${rejected.joinToString { it.id }}"
                }
                rejected.isEmpty()
            }
            if (next.size == kept.size) break
            kept = next
        }
        val ids = kept.flatMap { it.introductionGroups }.map { it.id }.toSet()
        kept = kept.map { set ->
            set.copy(introductionGroups = set.introductionGroups.map { group ->
                group.copy(next = group.next.filter { link ->
                    (link.groupId in ids).also {
                        if (!it) warnings += "grammar/${set.id}.json: dropped missing optional next target '${link.groupId}'"
                    }
                })
            })
        }
        return Resolution(kept, errors, warnings)
    }

    private fun invalidPrerequisites(groups: Map<String, GrammarIntroductionGroup>): Set<String> {
        val remaining = groups.mapValues { it.value.requires.size }.toMutableMap()
        val dependents = groups.values.flatMap { group -> group.requires.map { it to group.id } }.groupBy({ it.first }, { it.second })
        val ready = ArrayDeque(remaining.filterValues { it == 0 }.keys)
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            remaining.remove(id)
            dependents[id].orEmpty().forEach { dependent ->
                val degree = remaining.getValue(dependent) - 1
                remaining[dependent] = degree
                if (degree == 0) ready.addLast(dependent)
            }
        }
        // Missing references and cycles both leave their dependents unresolved.
        return remaining.keys
    }
}
