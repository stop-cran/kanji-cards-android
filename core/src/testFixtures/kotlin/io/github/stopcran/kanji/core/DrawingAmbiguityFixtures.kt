package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.LookalikeReference
import io.github.stopcran.kanji.core.draw.Stroke
import kotlinx.serialization.json.Json

object DrawingAmbiguityFixtures {
    data class Case(val id: String, val target: String, val alternative: String, val drawn: List<Stroke>)

    private val rows = checkNotNull(FixtureResources::class.java.getResourceAsStream("/drawing-ambiguity-cases.tsv"))
        .bufferedReader().useLines { lines -> lines.filterNot { it.startsWith("#") }.map { it.split("\t") }.toList() }

    val references: Map<String, List<Stroke>> = rows.filter { it[0] == "reference" }.associate { it[1] to parse(it[2]) }
    val cases: List<Case> = rows.filter { it[0] == "case" }.map { Case(it[1], it[2], it[3], parse(it[4])) }
    val pool = references.map { it.key to it.value }
    val alternatives = references.map { LookalikeReference(it.key, it.value) }

    fun strokesJson(kanji: String, orderVariants: List<List<Int>> = emptyList()): String {
        val strokes = references.getValue(kanji)
        val data = StrokeData(
            1, kanji, listOf(0.0, 0.0, 109.0, 109.0), strokes.size,
            strokes.mapIndexed { index, stroke ->
                io.github.stopcran.kanji.core.content.Stroke(index + 1, points = stroke.map { listOf(it.x, it.y) })
            },
            orderVariants = orderVariants,
        )
        return Json.encodeToString(StrokeData.serializer(), data)
    }

    private fun parse(geometry: String): List<Stroke> = geometry.split("|").map { stroke ->
        stroke.split(";").map { point ->
            val (x, y) = point.split(",")
            Pt(x.toDouble(), y.toDouble())
        }
    }
}
