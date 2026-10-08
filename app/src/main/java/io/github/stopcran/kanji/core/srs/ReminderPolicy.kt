package io.github.stopcran.kanji.core.srs

/**
 * When to nudge the user. Gentle by design: reminders back off while they are ignored (1, 2, 4, 7, 14 days apart) and stop
 * after the last gap, until the user studies again.
 */
object ReminderPolicy {
    private val gapDays = listOf(1, 2, 4, 7, 14)
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val SLACK_MS = 2L * 60 * 60 * 1000

    data class Decision(val remind: Boolean, val ignored: Int)

    /**
     * @param startOfTodayMs local midnight; studying today means no reminder.
     * @param ignored reminders sent since the last study session, as persisted by the caller.
     */
    fun decide(nowMs: Long, startOfTodayMs: Long, lastStudyMs: Long?, lastReminderMs: Long, ignored: Int, dueCount: Int): Decision {
        val ign = if (lastStudyMs != null && lastStudyMs > lastReminderMs) 0 else ignored
        if (dueCount <= 0) return Decision(false, ign)
        if (lastStudyMs != null && lastStudyMs >= startOfTodayMs) return Decision(false, ign)
        if (ign >= gapDays.size) return Decision(false, ign)
        if (lastReminderMs > 0 && nowMs - lastReminderMs < gapDays[ign] * DAY_MS - SLACK_MS) return Decision(false, ign)
        return Decision(true, ign + 1)
    }
}
