package io.github.stopcran.kanji.ui

import android.net.Uri
import io.github.stopcran.kanji.restoreStudyRoutes
import java.util.UUID

sealed interface AppDestination {
    val route: String

    data class Draw(val sessionId: String = UUID.randomUUID().toString(), val extra: Boolean = false) : AppDestination {
        init { require(sessionId.isNotBlank() && ':' !in sessionId) }
        override val route get() = "draw:$sessionId" + if (extra) ":extra" else ""
    }

    data class Document(val path: String, val sourceId: String? = null, val topic: String? = null) : AppDestination {
        init {
            require(DOCUMENT_PATH.matches(path))
            require(sourceId == null || sourceId.isNotBlank())
            require(sourceId != null || topic == null)
        }
        override val route get() = if (sourceId == null) "doc:$path" else {
            "source-doc:${Uri.encode(sourceId)}:${Uri.encode(path)}" + (topic?.let { ":${Uri.encode(it)}" } ?: "")
        }
    }

    data class Legacy(override val route: String) : AppDestination
    data object Unavailable : AppDestination { override val route = "destination-unavailable" }

    companion object {
        private val DOCUMENT_PATH = Regex("""(?:kanji|words|articles)/[^/\\]+\.md""")

        /** Compatibility boundary only; migrated screens consume the typed values. */
        fun parse(route: String): AppDestination = try {
            when {
                route == Unavailable.route -> Unavailable
                route.startsWith("draw:") -> {
                    val parts = route.split(':')
                    require(parts.size == 2 || (parts.size == 3 && parts[2] == "extra"))
                    Draw(parts[1], parts.size == 3)
                }
                route.startsWith("doc:") -> Document(route.removePrefix("doc:"))
                route.startsWith("source-doc:") -> {
                    val parts = route.removePrefix("source-doc:").split(':', limit = 3).map(Uri::decode)
                    require(parts.size >= 2)
                    Document(parts[1], parts[0], parts.getOrNull(2)?.takeIf { it.isNotBlank() })
                }
                else -> Legacy(route)
            }
        } catch (_: IllegalArgumentException) {
            Unavailable
        }
    }
}

data class NavigationEntry(val destination: AppDestination, val id: String = UUID.randomUUID().toString()) {
    val scoped get() = destination is AppDestination.Draw || destination is AppDestination.Document
    fun save() = "entry|$id|${destination.route}"

    companion object {
        fun restore(saved: List<String>, sameProcess: Boolean): List<NavigationEntry> {
            val entries = saved.map { record ->
                val parts = record.split('|', limit = 3)
                if (parts.size == 3 && parts[0] == "entry") NavigationEntry(AppDestination.parse(parts[2]), parts[1])
                else NavigationEntry(AppDestination.parse(record))
            }
            return entries.mapNotNull { entry ->
                restoreStudyRoutes(listOf(entry.destination.route), sameProcess).singleOrNull()?.let {
                    entry.copy(destination = AppDestination.parse(it))
                }
            }.fold(emptyList()) { result, entry ->
                val previous = result.lastOrNull()
                val duplicate = previous != null &&
                    restoreStudyRoutes(listOf(previous.destination.route, entry.destination.route), sameProcess = true).size == 1
                (if (duplicate) result.dropLast(1) else result) + entry
            }
        }
    }
}
