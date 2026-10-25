package io.github.stopcran.kanji.core.counters

enum class CounterScope(val code: String, val label: String, private val minimumLevel: Int) {
    N5("n5", "N5", 5),
    N4("n4", "N4 + N5", 4),
    All("all", "All levels", 1);

    fun includes(level: Int): Boolean = level in minimumLevel..5

    companion object {
        // Routes written before level selection represented all imported levels.
        fun fromRoute(route: String) = entries.firstOrNull { it.code == route.split(':').getOrNull(4) } ?: All
    }
}

object CounterRoutes {
    fun create(
        mode: CounterMode,
        group: CounterGroup,
        scope: CounterScope,
        extra: Boolean = false,
        stamp: Long = System.currentTimeMillis(),
    ): String = "cquiz:$stamp:${mode.code}:${group.code}:${scope.code}" + if (extra) ":extra" else ""

    fun extra(route: String): String = create(
        CounterMode.fromCode(route.split(':').getOrNull(2)),
        CounterGroup.fromRoute(route),
        CounterScope.fromRoute(route),
        extra = true,
    )
}
