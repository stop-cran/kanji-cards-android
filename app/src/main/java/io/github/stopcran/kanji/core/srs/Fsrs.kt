package io.github.stopcran.kanji.core.srs

import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Grade(val value: Int) { Again(1), Hard(2), Good(3), Easy(4) }

enum class CardPhase { New, Learning, Review }

data class SrsState(
    val phase: CardPhase = CardPhase.New,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Instant = Instant.EPOCH,
    val lastReview: Instant? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
)

/**
 * FSRS-5 scheduler with the published default parameters. Sub-day steps are used only after a lapse
 * ("Again"); everything else is scheduled in whole days.
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_WEIGHTS,
    val requestRetention: Double = 0.9,
    private val maxIntervalDays: Int = 36_500,
    private val againStep: Duration = Duration.ofMinutes(10),
) {
    fun retrievability(state: SrsState, now: Instant): Double {
        val last = state.lastReview ?: return 0.0
        val elapsed = max(0.0, Duration.between(last, now).toMillis() / MILLIS_PER_DAY)
        return (1 + FACTOR * elapsed / state.stability).pow(DECAY)
    }

    fun review(state: SrsState, grade: Grade, now: Instant): SrsState {
        val g = grade.value
        val last = state.lastReview
        val next = if (state.phase == CardPhase.New || last == null) {
            state.copy(stability = initStability(g), difficulty = initDifficulty(g))
        } else {
            val elapsedDays = max(0.0, Duration.between(last, now).toMillis() / MILLIS_PER_DAY)
            val r = retrievability(state, now)
            val d = nextDifficulty(state.difficulty, g)
            val s = when {
                elapsedDays < 1.0 -> shortTermStability(state.stability, g)
                g == 1 -> forgetStability(state.difficulty, state.stability, r)
                else -> recallStability(state.difficulty, state.stability, r, g)
            }
            state.copy(stability = s, difficulty = d)
        }

        val lapse = g == 1 && state.phase == CardPhase.Review
        val result = next.copy(lastReview = now, reps = state.reps + 1, lapses = state.lapses + if (lapse) 1 else 0)
        return if (g == 1) {
            result.copy(phase = CardPhase.Learning, due = now.plus(againStep))
        } else {
            result.copy(phase = CardPhase.Review, due = now.plus(Duration.ofDays(intervalDays(result.stability))))
        }
    }

    fun intervalDays(stability: Double): Long {
        val raw = stability / FACTOR * (requestRetention.pow(1 / DECAY) - 1)
        return raw.roundToLong().coerceIn(1L, maxIntervalDays.toLong())
    }

    private fun initStability(g: Int) = max(w[g - 1], 0.1)

    private fun initDifficulty(g: Int) = (w[4] - exp(w[5] * (g - 1)) + 1).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, g: Int): Double {
        val delta = -w[6] * (g - 3)
        val damped = d + delta * (10 - d) / 9
        val mixed = w[7] * initDifficulty(4) + (1 - w[7]) * damped
        return mixed.coerceIn(1.0, 10.0)
    }

    private fun recallStability(d: Double, s: Double, r: Double, g: Int): Double {
        val hard = if (g == 2) w[15] else 1.0
        val easy = if (g == 4) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hard * easy)
    }

    private fun forgetStability(d: Double, s: Double, r: Double): Double {
        val raw = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
        return min(raw, s)
    }

    private fun shortTermStability(s: Double, g: Int): Double {
        val factor = exp(w[17] * (g - 3 + w[18]))
        val raw = s * if (g >= 3) max(factor, 1.0) else factor
        return max(raw, 0.1)
    }

    companion object {
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0
        private const val MILLIS_PER_DAY = 86_400_000.0

        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
            1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
        )
    }
}
