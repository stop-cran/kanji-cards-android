package io.github.stopcran.kanji.core.srs

/** Counts durable, skill-specific evidence rather than unique cards or session attempts. */
data class ProgressCount(val solid: Int, val total: Int) {
    init {
        require(solid in 0..total)
    }

    val fraction: Float get() = if (total == 0) 0f else solid.toFloat() / total
    val percent: Int? get() = if (total == 0) null else solid * 100 / total
}

data class StudyProgress(val skills: Map<String, ProgressCount>) {
    val total: ProgressCount
        get() = ProgressCount(skills.values.sumOf { it.solid }, skills.values.sumOf { it.total })
}
