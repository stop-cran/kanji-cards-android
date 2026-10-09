package io.github.stopcran.kanji.core

import io.github.stopcran.kanji.core.content.StrokeData
import io.github.stopcran.kanji.core.draw.IssueType
import io.github.stopcran.kanji.core.draw.Pt
import io.github.stopcran.kanji.core.draw.Stroke
import io.github.stopcran.kanji.core.draw.StrokeMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real drawings exported from a phone (test resources `drawings/`), graded against the content repo's strokes. */
class RealDrawingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun reference(kanji: String): List<Stroke> {
        val f = File("../../learning-japanese/strokes/$kanji.json")
        assumeTrue(f.exists())
        return json.decodeFromString(StrokeData.serializer(), f.readText()).strokes.map { s -> s.points.map { Pt(it[0], it[1]) } }
    }

    @Test
    fun realDrawingsKeepTheirVerdicts() {
        val dir = File("src/test/resources/drawings")
        for (f in dir.listFiles { x -> x.extension == "json" }!!.sortedBy { it.name }) {
            val o = json.parseToJsonElement(f.readText()).jsonObject
            val kanji = o["kanji"]!!.jsonPrimitive.content
            val drawn = o["strokes"]!!.jsonArray.map { s -> s.jsonArray.map { p -> Pt(p.jsonArray[0].jsonPrimitive.double, p.jsonArray[1].jsonPrimitive.double) } }
            val r = StrokeMatcher().match(reference(kanji), drawn)
            val logged = o["outcome"]!!.jsonPrimitive.content
            if (kanji == "水") assertTrue("$kanji: missing hook expected", r.issues.any { it.type == IssueType.MissingHook })
            else if (logged == "Clean") assertTrue("$kanji ${f.name}: ${r.issues}", r.clean)
        }
    }
}
