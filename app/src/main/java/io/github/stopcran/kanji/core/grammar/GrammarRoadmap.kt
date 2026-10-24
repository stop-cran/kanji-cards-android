package io.github.stopcran.kanji.core.grammar

import io.github.stopcran.kanji.core.content.GrammarLeafEdge
import java.util.PriorityQueue

data class GrammarMapPosition(val id: String, val column: Int, val row: Int)

data class GrammarMapLayout(val positions: List<GrammarMapPosition>, val edges: List<GrammarLeafEdge>) {
    val columns: Int = (positions.maxOfOrNull { it.column } ?: -1) + 1
    val rows: Int = (positions.maxOfOrNull { it.row } ?: -1) + 1
}

/** Topology and stable IDs alone determine placement; learning state and labels never move a node. */
object GrammarRoadmap {
    fun layout(ids: Collection<String>, edges: List<GrammarLeafEdge>): GrammarMapLayout {
        require(ids.toSet().size == ids.size) { "Duplicate roadmap node." }
        val known = ids.toSet()
        require(edges.distinct().size == edges.size && edges.all { it.foundation in known && it.dependent in known }) {
            "Invalid roadmap edge."
        }
        val incoming = edges.groupBy { it.dependent }
        val outgoing = edges.groupBy { it.foundation }
        val remaining = ids.associateWith { incoming[it].orEmpty().size }.toMutableMap()
        val queue = PriorityQueue(remaining.filterValues { it == 0 }.keys)
        val depth = mutableMapOf<String, Int>()
        while (queue.isNotEmpty()) {
            val id = queue.remove()
            depth[id] = incoming[id].orEmpty().maxOfOrNull { depth.getValue(it.foundation) + 1 } ?: 0
            outgoing[id].orEmpty().forEach { edge ->
                remaining[edge.dependent] = remaining.getValue(edge.dependent) - 1
                if (remaining.getValue(edge.dependent) == 0) queue.add(edge.dependent)
            }
        }
        require(depth.size == ids.size) { "Cyclic roadmap prerequisites." }
        val positions = mutableMapOf<String, GrammarMapPosition>()
        depth.entries.groupBy({ it.value }, { it.key }).toSortedMap().forEach { (column, layer) ->
            val ordered = layer.sortedWith(compareBy<String> { id ->
                incoming[id].orEmpty().map { positions.getValue(it.foundation).row }.average().takeUnless { it.isNaN() } ?: 0.0
            }.thenBy { it })
            ordered.forEachIndexed { row, id -> positions[id] = GrammarMapPosition(id, column, row) }
        }
        return GrammarMapLayout(positions.values.toList(), edges.sortedWith(compareBy({ it.foundation }, { it.dependent })))
    }
}

data class GrammarMapGroup(
    val group: IntroductionGroup,
    val availability: GrammarGroupAvailability,
    val evidence: GroupEvidence,
    val ready: Boolean,
    val matching: Int?,
    val articles: Map<String, String>,
    val readinessProblem: String? = null,
) {
    val practiceAvailable: Boolean get() = availability.started.isNotEmpty() || availability.targetedNew.isNotEmpty()
    val newNeedsFoundation: Boolean get() =
        availability.newMetadataProblem == null && readinessProblem == null &&
            availability.inScopeUnseen.isNotEmpty() && availability.missingPrerequisites.isNotEmpty()
    val progress: GrammarMapProgress get() = GrammarMapProgress(
        1, group.members.size, matching, availability.started.size, availability.due.size,
        if (group.authored) 1 else 0, if (ready) 1 else 0, if (evidence.familiar) 1 else 0,
        if (practiceAvailable) 1 else 0, if (newNeedsFoundation) 1 else 0,
    )
}

data class GrammarMapProgress(
    val groups: Int,
    val total: Int,
    val matching: Int?,
    val introduced: Int,
    val due: Int,
    val authored: Int,
    val ready: Int,
    val familiar: Int,
    val available: Int,
    val needsFoundation: Int,
) {
    companion object {
        fun of(groups: List<GrammarMapGroup>): GrammarMapProgress {
            val all = groups.map { it.progress }
            val matching = if (all.any { it.matching == null }) null else all.sumOf { requireNotNull(it.matching) }
            return GrammarMapProgress(
                all.sumOf { it.groups }, all.sumOf { it.total }, matching, all.sumOf { it.introduced }, all.sumOf { it.due },
                all.sumOf { it.authored }, all.sumOf { it.ready }, all.sumOf { it.familiar },
                all.sumOf { it.available }, all.sumOf { it.needsFoundation },
            )
        }
    }
}
