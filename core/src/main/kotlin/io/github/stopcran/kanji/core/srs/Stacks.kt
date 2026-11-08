package io.github.stopcran.kanji.core.srs

/** A named tag filter; JLPT selections additionally include started cards from easier levels. */
data class Stack(val id: String, val label: String, val tag: String?) {
    fun contains(tags: List<String>) = tag == null || tag in tags
}

object Stacks {
    const val ALL = "all"
    const val JLPT_STATE = "kanji-jlpt"
    val everything = Stack(ALL, "All", null)

    val jlpt = listOf(
        Stack("jlpt-n5", "N5", "jlpt-n5"),
        Stack("jlpt-n4", "N4", "jlpt-n4"),
        Stack("jlpt-n3", "N3", "jlpt-n3"),
        Stack("jlpt-n2", "N2", "jlpt-n2"),
        Stack("jlpt-n1", "N1", "jlpt-n1"),
    )
    private val known = jlpt + listOf(
        everything,
        Stack("starter", "Starter", "starter"),
    )

    fun jlptLevel(id: String): Int? = jlpt.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { 5 - it }

    /** A card with conflicting level tags is not a reliable cross-level transition target. */
    fun jlptLevel(tags: List<String>): Int? = jlpt.mapNotNull { s -> jlptLevel(s.id)?.takeIf { s.contains(tags) } }.singleOrNull()

    fun stateId(stackId: String): String = if (jlptLevel(stackId) != null) JLPT_STATE else stackId

    /** The full review and answer-option range; [Stack.contains] remains the exact new-card filter. */
    fun includes(stack: Stack, tags: List<String>): Boolean {
        val ceiling = jlptLevel(stack.id) ?: return stack.contains(tags)
        return (ceiling..5).any { "jlpt-n$it" in tags }
    }

    /** Available levels, All (even without cards), then thematic stacks. */
    fun available(cardTags: List<List<String>>): List<Stack> =
        known.filter { s -> s.tag == null || cardTags.any { s.contains(it) } }

    fun find(id: String, stacks: List<Stack>): Stack = stacks.firstOrNull { it.id == id } ?: everything
}
