package io.github.stopcran.kanji.core.srs

/**
 * A stack is a tag filter over the cards. Every stack keeps its own scheduling state, so a kanji learned within a small
 * set is scheduled afresh when it is studied among many (where it may get confused with look-alikes).
 */
data class Stack(val id: String, val label: String, val tag: String?) {
    fun contains(tags: List<String>) = tag == null || tag in tags
}

object Stacks {
    const val ALL = "all"
    val everything = Stack(ALL, "All", null)

    private val known = listOf(
        Stack("jlpt-n5", "N5", "jlpt-n5"),
        Stack("jlpt-n4", "N4", "jlpt-n4"),
        Stack("jlpt-n3", "N3", "jlpt-n3"),
        Stack("jlpt-n2", "N2", "jlpt-n2"),
        Stack("jlpt-n1", "N1", "jlpt-n1"),
        everything,
        Stack("starter", "Starter", "starter"),
    )

    /** Available levels, All (even without cards), then thematic stacks. */
    fun available(cardTags: List<List<String>>): List<Stack> =
        known.filter { s -> s.tag == null || cardTags.any { s.contains(it) } }

    fun find(id: String, stacks: List<Stack>): Stack = stacks.firstOrNull { it.id == id } ?: everything
}
