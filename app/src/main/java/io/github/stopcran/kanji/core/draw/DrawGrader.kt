package io.github.stopcran.kanji.core.draw

enum class DrawOutcome { NotRecognized, Mistakes, Clean }

object DrawGrader {
    /**
     * Combines the recognition gate with the stroke matcher.
     * - A drawing whose strokes all match the reference is clean, whatever the recogniser says.
     * - Otherwise the drawing must be recognised (top [topN] candidates, or by the matcher when the recogniser
     *   is unavailable, i.e. [candidates] is null); if it is, the stroke issues are the mistakes, if not it is rejected.
     */
    fun outcome(candidates: List<String>?, target: String, match: MatchResult, topN: Int = 5, strokeLookalike: String? = null): DrawOutcome {
        if (strokeLookalike != null) return DrawOutcome.NotRecognized
        if (match.drawnStrokes > 0 && match.clean) return DrawOutcome.Clean
        val recognized = candidates?.take(topN)?.any { it == target } ?: match.recognizable
        return if (recognized && match.drawnStrokes > 0) DrawOutcome.Mistakes else DrawOutcome.NotRecognized
    }
}

/** Human-readable description with 1-based stroke numbers. */
fun StrokeIssue.describe(): String {
    val r = refIndex?.plus(1)
    val d = drawnIndex?.plus(1)
    return when (type) {
        IssueType.Reversed -> "Stroke $r was drawn in the wrong direction"
        IssueType.WrongOrder -> "Stroke $r was drawn out of order (you drew it as number $d)"
        IssueType.WrongShape -> "Stroke $r has the wrong shape, position or length"
        IssueType.Joined -> "Strokes $r and ${r!! + 1} were joined into one"
        IssueType.Broken -> "Stroke $r was broken into two"
        IssueType.Missing -> "Stroke $r is missing"
        IssueType.Extra -> "Extra stroke (your number $d)"
        IssueType.MissingHook -> "Stroke $r needs a hook at its end"
        IssueType.ExtraHook -> "Stroke $r should end straight, without a hook"
    }
}
