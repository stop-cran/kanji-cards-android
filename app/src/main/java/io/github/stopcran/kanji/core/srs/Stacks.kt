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
    val everything = Stack(ALL, "All cards", null)

    private val known = listOf(
        Stack("starter", "Starter", "starter"),
        Stack("jlpt-n5", "JLPT N5", "jlpt-n5"),
        Stack("jlpt-n4", "JLPT N4", "jlpt-n4"),
        Stack("jlpt-n3", "JLPT N3", "jlpt-n3"),
        Stack("jlpt-n2", "JLPT N2", "jlpt-n2"),
        Stack("jlpt-n1", "JLPT N1", "jlpt-n1"),
    )

    /** "All cards" plus every known stack that has at least one card (given each card's tags). */
    fun available(cardTags: List<List<String>>): List<Stack> =
        listOf(everything) + known.filter { s -> cardTags.any { s.contains(it) } }

    fun find(id: String, stacks: List<Stack>): Stack = stacks.firstOrNull { it.id == id } ?: everything
}
