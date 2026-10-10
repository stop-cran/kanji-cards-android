package io.github.stopcran.kanji.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.stopcran.kanji.KanjiApp
import io.github.stopcran.kanji.MainActivity
import io.github.stopcran.kanji.core.srs.QueueBuilder
import io.github.stopcran.kanji.core.srs.ReminderPolicy
import io.github.stopcran.kanji.core.srs.StudyMode
import io.github.stopcran.kanji.core.words.WordDirection
import io.github.stopcran.kanji.core.words.WordQueues
import io.github.stopcran.kanji.core.words.WordStacks
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import io.github.stopcran.kanji.core.draw.DrawGate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Runs once a day around the chosen hour; the actual nudging rules live in [ReminderPolicy]. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KanjiApp
        if (!app.settings.reminderEnabled.value) return Result.success()
        run {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val source = app.settings.source.value
            val stack = app.settings.stack.value
            val cards = app.db.content().kanjiLite(source.id).inStack(stack)
            val newRemaining = app.db.newAllowance(app.settings, source.id, Instant.ofEpochMilli(now)).remaining
            var due = 0
            val learned = app.db.meaningLearned(app.settings, source.id, stack)
            val focus = app.db.focusStatus(app.settings, source.id, stack, cards, now)
            for (mode in listOf(StudyMode.Quiz, StudyMode.Draw)) {
                val states = app.db.reviews().states(source.id, stack, mode.name).associate { it.kanji to it.toSrs() }
                val ids = cards.filter { mode == StudyMode.Quiz || it.strokesJson != null }.map { it.kanji }
                    .let { if (mode == StudyMode.Draw) DrawGate.eligible(it, states, learned) else it }
                val newLeft = if (mode == StudyMode.Quiz) minOf(newRemaining, focus?.capacity ?: newRemaining) else newRemaining
                due += QueueBuilder.build(ids, states, Instant.ofEpochMilli(now), newLeft).size
            }
            val wordStack = WordStacks.find(app.settings.wordStack.value, WordStacks.offered(app.settings.n4Unlocked.value))
            val wordEntities = app.db.content().words(source.id).inWordStack(wordStack, app.db.content().kanjiLite(source.id).levels())
            val wordStates = WordDirection.entries.associateWith { d -> app.db.reviews().states(source.id, wordStack.id, d.mode.name).associate { it.kanji to it.toSrs() } }
            due += app.db.readingData(app.settings, source.id, stack, cards, newRemaining, Instant.ofEpochMilli(now), false).items.size
            for (d in WordDirection.entries) {
                due += WordQueues.build(d, wordEntities.forDirection(d).map { it.word }, wordStates.getValue(d), wordStates.getValue(d.other), Instant.ofEpochMilli(now), newRemaining).size
            }
            val decision = ReminderPolicy.decide(
                now, LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli(), app.db.reviews().lastReviewMs(),
                app.settings.lastReminderMs, app.settings.remindersIgnored, due,
            )
            app.settings.remindersIgnored = decision.ignored
            if (decision.remind && notify(due)) app.settings.lastReminderMs = now
        }
        return Result.success()
    }

    private fun notify(due: Int): Boolean {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Study reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(io.github.stopcran.kanji.R.drawable.ic_stat_reminder)
            .setContentTitle("Time for your kanji")
            .setContentText("$due cards are waiting for you.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(1, n)
        return true
    }

    companion object {
        private const val CHANNEL = "reminders"
        private const val NAME = "study-reminder"

        /** Schedules the daily run starting at the next [hour]:00 local time; [replace] restarts it (settings changed). */
        fun schedule(context: Context, hour: Int, replace: Boolean) {
            val zone = ZoneId.systemDefault()
            val nowTime = LocalDateTime.now(zone)
            var next = LocalDateTime.of(nowTime.toLocalDate(), LocalTime.of(hour, 0))
            if (!next.isAfter(nowTime.plusMinutes(1))) next = next.plusDays(1)
            val delayMs = java.time.Duration.between(nowTime, next).toMillis()
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
