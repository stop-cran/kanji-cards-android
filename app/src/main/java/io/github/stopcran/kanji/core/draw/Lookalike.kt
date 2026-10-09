package io.github.stopcran.kanji.core.draw

/**
 * Catches a drawing that is clean for the asked kanji but fits another kanji clearly better, e.g. 牛 drawn for 午:
 * the two differ by one stroke relation (crossing vs attaching) that the per-stroke tolerance cannot see, but the other
 * kanji's own reference strokes can.
 */
class LookalikeGate(
    private val matcher: StrokeMatcher = StrokeMatcher(),
    /** The other kanji must fit at most this fraction of the asked kanji's cost... */
    private val relative: Double = DEFAULT_RELATIVE,
    /** ...and be better by at least this much in absolute terms. */
    private val absolute: Double = DEFAULT_ABSOLUTE,
) {
    /** The better-fitting kanji from [pool] (kanji to reference strokes), or null when the drawing is just the asked one. */
    fun find(target: String, targetMatch: MatchResult, drawn: List<Stroke>, pool: Iterable<Pair<String, List<Stroke>>>): String? {
        if (!targetMatch.clean || targetMatch.drawnStrokes == 0) return null
        var best: String? = null
        var bestCost = minOf(targetMatch.fitCost * relative, targetMatch.fitCost - absolute)
        for ((kanji, ref) in pool) {
            if (kanji == target || ref.size != targetMatch.referenceStrokes) continue
            val r = matcher.match(ref, drawn)
            if (r.clean && r.fitCost < bestCost) { best = kanji; bestCost = r.fitCost }
        }
        return best
    }

    companion object {
        const val DEFAULT_RELATIVE = 0.8
        const val DEFAULT_ABSOLUTE = 0.01
    }
}

/** True for exactly one code point in the Han script (supplementary-plane kanji are two UTF-16 chars). */
fun isSingleHan(s: String): Boolean =
    s.isNotEmpty() && s.codePointCount(0, s.length) == 1 && Character.UnicodeScript.of(s.codePointAt(0)) == Character.UnicodeScript.HAN
