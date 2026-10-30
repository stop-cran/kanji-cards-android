package io.github.stopcran.kanji.core.listening

enum class ListeningScope(val code: String, val label: String, private val minimumLevel: Int) {
    N5("n5", "N5", 5),
    N4("n4", "N4", 4);

    fun includes(level: Int): Boolean = level in minimumLevel..5
}

data class ListeningSelection(val scope: ListeningScope, val format: String = "all") {
    init {
        require(format in setOf("all", "legacy") || ListeningTaskType.entries.any { it.wire == format }) {
            "Unknown listening format"
        }
    }

    fun includes(level: Int, itemFormat: String): Boolean =
        scope.includes(level) && (format == "all" || format == itemFormat)
}
