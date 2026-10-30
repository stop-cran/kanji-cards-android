package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarExercise
import io.github.stopcran.kanji.core.content.GrammarSet
import io.github.stopcran.kanji.core.content.ContentParser
import java.time.Duration
import java.time.Instant

data class GrammarFacetKey(val groupId: String, val facetId: String)
data class GrammarAttainment(val key: GrammarFacetKey, val atMs: Long)

object GrammarReadiness {
    const val POLICY_VERSION = 1

    fun mappingHash(sets: List<GrammarSet>): String {
        val exercises = sets.flatMap { it.exercises }.associateBy { it.id }
        val text = buildString {
            appendLine(POLICY_VERSION)
            GrammarFocus.groups(sets).filter { it.authored }.sortedBy { it.id }.forEach { group ->
                appendLine(group.id)
                group.facets.sortedBy { it.id }.forEach { appendLine(it.id) }
                group.members.sortedBy { it.exerciseId }.forEach { member ->
                    val exercise = exercises.getValue(member.exerciseId)
                    appendLine(listOf(member.exerciseId, member.facetId, member.exampleKey, exercise.target, exercise.relationship).joinToString(":"))
                }
            }
        }
        return ContentParser.rawSha256(text.toByteArray(Charsets.UTF_8))
    }

    fun readyGroups(groups: List<IntroductionGroup>, attained: Set<GrammarFacetKey>): Set<String> =
        groups.filter { group ->
            group.authored && group.facets.isNotEmpty() && group.facets.all { GrammarFacetKey(group.id, it.id) in attained }
        }.mapTo(hashSetOf()) { it.id }

    /** A chronological scan: bounded query pages and at most one 21-day positive window per unearned facet. */
    class History(
        groups: List<IntroductionGroup>,
        private val exercises: Map<String, GrammarExercise>,
        attained: Set<GrammarFacetKey>,
        private val now: Instant,
    ) {
        private data class Member(val key: GrammarFacetKey, val exampleKey: String)
        private data class Positive(val atMs: Long, val exampleKey: String, val typed: Boolean)
        private class Window {
            val events = ArrayDeque<Positive>()
            val contexts = hashMapOf<String, Int>()
            var typed = 0
            fun add(value: Positive) {
                events.addLast(value)
                contexts[value.exampleKey] = contexts.getOrDefault(value.exampleKey, 0) + 1
                if (value.typed) typed++
            }
            fun trim(since: Long) {
                while (events.isNotEmpty() && events.first().atMs < since) {
                    val event = events.removeFirst()
                    val left = contexts.getValue(event.exampleKey) - 1
                    if (left == 0) contexts.remove(event.exampleKey) else contexts[event.exampleKey] = left
                    if (event.typed) typed--
                }
            }
            fun supports(): Boolean = contexts.size >= 2 && typed > 0 &&
                events.last().atMs - events.first().atMs >= Duration.ofHours(24).toMillis()
        }
        private val members = groups.filter { it.authored }.flatMap { group ->
            group.members.map { it.exerciseId to Member(GrammarFacetKey(group.id, it.facetId), it.exampleKey) }
        }.toMap()
        private val earned = attained.toMutableSet()
        private val windows = hashMapOf<GrammarFacetKey, Window>()
        private val resets = hashMapOf<GrammarFacetKey, Long>()
        private val changed = hashSetOf<GrammarFacetKey>()
        private var timestamp: Long? = null
        val diagnostics = linkedSetOf<String>()

        fun accept(event: GrammarEvent): List<GrammarAttainment> {
            require(timestamp == null || event.atMs >= requireNotNull(timestamp)) { "Readiness history must be chronological" }
            val results = if (timestamp != null && timestamp != event.atMs) finishTimestamp() else emptyList()
            timestamp = event.atMs
            val member = members[event.exerciseId] ?: return results
            if (member.key in earned) return results
            val messages = mutableListOf<String>()
            if (event.atMs > now.toEpochMilli() ||
                !GrammarEvidence.validEvent(event, exercises.getValue(event.exerciseId), Instant.ofEpochMilli(event.atMs), messages)) {
                // Bound diagnostic storage too; detailed event problems remain available in the recent-evidence readout.
                diagnostics += messages.ifEmpty { listOf("Future-dated history excluded; check the device clock.") }
                    .filterNot { it in diagnostics }.take(20 - diagnostics.size)
                return results
            }
            diagnostics += messages.filterNot { it in diagnostics }.take(20 - diagnostics.size)
            changed += member.key
            if (event.grade in 1..2 || event.correct == false) {
                windows.remove(member.key)
                resets[member.key] = event.atMs
            } else if (event.atMs > resets.getOrDefault(member.key, Long.MIN_VALUE)) {
                val window = windows.getOrPut(member.key) { Window() }
                window.trim(event.atMs - GrammarEvidence.WINDOW.toMillis())
                window.add(Positive(event.atMs, member.exampleKey, event.promptType == "type"))
            }
            return results
        }

        fun finish(): List<GrammarAttainment> = finishTimestamp()

        private fun finishTimestamp(): List<GrammarAttainment> {
            val at = timestamp ?: return emptyList()
            val result = changed.filter { windows[it]?.supports() == true }.map { GrammarAttainment(it, at) }
            result.forEach { earned += it.key; windows.remove(it.key) }
            changed.clear()
            return result
        }
    }
}
