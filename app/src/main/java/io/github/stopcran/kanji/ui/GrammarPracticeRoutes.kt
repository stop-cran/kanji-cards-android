package io.github.stopcran.kanji.ui

import android.net.Uri
import android.util.Log
import io.github.stopcran.kanji.core.grammar.GrammarPracticeTarget
import kotlinx.serialization.json.Json
import java.util.UUID

internal object GrammarPracticeRoutes {
    const val PICKER = "grammar-topics"
    const val UNAVAILABLE = "grammar-practice-unavailable"

    fun create(target: GrammarPracticeTarget, interrupted: Boolean = false): String =
        "grammarquiz:${UUID.randomUUID()}:target:${Uri.encode(Json.encodeToString(GrammarPracticeTarget.serializer(), target))}" +
            if (interrupted) ":restart" else ""

    fun isTargeted(route: String): Boolean =
        route.startsWith("grammarquiz:") && route.split(":", limit = 4).getOrNull(2) == "target"

    fun isOrdinary(route: String): Boolean = route.split(":").let {
        it.first() == "grammarquiz" && it.getOrNull(1)?.isNotBlank() == true &&
            (it.size == 2 || (it.size == 3 && it[2] == "extra"))
    }

    fun target(route: String): GrammarPracticeTarget {
        val parts = route.split(":")
        require(isTargeted(route) && parts.size in 4..5 && (parts.size == 4 || parts[4] == "restart")) {
            "Invalid targeted-practice route."
        }
        return Json.decodeFromString(GrammarPracticeTarget.serializer(), Uri.decode(parts[3]))
    }

    fun restart(route: String): String = try {
        create(target(route), interrupted = true)
    } catch (error: IllegalArgumentException) {
        Log.w("GrammarPractice", "Cannot restore practice target", error)
        UNAVAILABLE
    }

    fun adaptDiscovery(route: String): String = try {
        when {
            route == PICKER -> "grammar-home"
            route.startsWith(GrammarRoadmapRoutes.PREFIX) -> GrammarRoadmapRoutes.parse(route).target()?.let { create(it) } ?: "grammar-home"
            else -> route
        }
    } catch (error: IllegalArgumentException) {
        Log.w("GrammarPractice", "Cannot restore Grammar discovery", error)
        UNAVAILABLE
    }

    /** Detail browsing state survives a new batch, but the quiz/consent identity remains the fresh route UUID. */
    fun savedStateIdentity(route: String): String = try {
        if (isTargeted(route)) "grammar-topic:" + Uri.encode(Json.encodeToString(GrammarPracticeTarget.serializer(), target(route))) else route
    } catch (error: IllegalArgumentException) {
        Log.w("GrammarPractice", "Invalid Grammar state identity", error)
        route
    }
}
