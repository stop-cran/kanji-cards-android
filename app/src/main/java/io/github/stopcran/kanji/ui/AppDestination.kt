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

    data class Document(
        val path: String,
        val sourceId: String? = null,
        val topic: String? = null,
        val sessionBound: Boolean = false,
        val reading: TextReading? = null,
    ) : AppDestination {
        init {
            require(DOCUMENT_PATH.matches(path))
            require(sourceId == null || sourceId.isNotBlank())
            require(sourceId != null || (topic == null && !sessionBound))
            require(reading == null || (reading.captureHash != null && reading.sourceId == sourceId && topic == null && !sessionBound))
        }
        override val route get() = if (reading != null) "reading-doc:${Uri.encode(reading.route)}:${Uri.encode(path)}"
        else if (sourceId == null) "doc:$path" else {
            val prefix = if (sessionBound) "session-doc" else "source-doc"
            "$prefix:${Uri.encode(sourceId)}:${Uri.encode(path)}" + (topic?.let { ":${Uri.encode(it)}" } ?: "")
        }
    }

    data class TextReading(val sourceId: String, val textId: String, val captureHash: String? = null) : AppDestination {
        init {
            require(sourceId.isNotBlank())
            require(Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(textId))
            require(captureHash == null || Regex("[a-f0-9]{64}").matches(captureHash))
        }
        override val route get() = "reading:${Uri.encode(sourceId)}:$textId" + (captureHash?.let { ":$it" } ?: "")
    }

    data class ReadingQuizStart(val reading: TextReading) : AppDestination {
        init { require(reading.captureHash != null) }
        override val route get() = "reading-quiz-start:${Uri.encode(reading.route)}"
    }

    data class ReadingQuiz(val id: String) : AppDestination {
        init { require(UUID.fromString(id).toString() == id) }
        override val route get() = "reading-quiz:$id"
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
                route.startsWith("reading:") || route.startsWith("reading-") -> parseReading(route)
                route.startsWith("source-doc:") || route.startsWith("session-doc:") -> {
                    val sessionBound = route.startsWith("session-doc:")
                    val prefix = if (sessionBound) "session-doc:" else "source-doc:"
                    val parts = route.removePrefix(prefix).split(':', limit = 3).map(Uri::decode)
                    require(parts.size >= 2)
                    Document(parts[1], parts[0], parts.getOrNull(2)?.takeIf { it.isNotBlank() }, sessionBound)
                }
                else -> Legacy(route)
            }
        } catch (_: IllegalArgumentException) {
            Unavailable
        }

        private fun parseReading(route: String): AppDestination = when {
            route.startsWith("reading:") -> {
                val parts = route.split(':')
                require(parts.size in 3..4)
                TextReading(Uri.decode(parts[1]), parts[2], parts.getOrNull(3))
            }
            route.startsWith("reading-doc:") -> {
                val parts = route.removePrefix("reading-doc:").split(':')
                require(parts.size == 2)
                val reading = parse(Uri.decode(parts[0])) as? TextReading
                require(reading?.captureHash != null)
                Document(Uri.decode(parts[1]), reading.sourceId, reading = reading)
            }
            route.startsWith("reading-quiz-start:") -> {
                val reading = parse(Uri.decode(route.removePrefix("reading-quiz-start:"))) as? TextReading
                ReadingQuizStart(requireNotNull(reading))
            }
            route.startsWith("reading-quiz:") -> ReadingQuiz(route.removePrefix("reading-quiz:"))
            else -> Legacy(route)
        }
    }
}

data class NavigationEntry(val destination: AppDestination, val id: String = UUID.randomUUID().toString()) {
    val scoped get() = destination is AppDestination.Draw || destination is AppDestination.Document ||
        destination is AppDestination.TextReading || destination is AppDestination.ReadingQuiz || destination is AppDestination.ReadingQuizStart
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
