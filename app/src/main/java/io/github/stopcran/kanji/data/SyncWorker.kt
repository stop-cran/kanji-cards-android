package io.github.stopcran.kanji.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.stopcran.kanji.KanjiApp
import java.util.concurrent.TimeUnit

enum class WorkOutcome { Success, Retry, Failure }

/** Only transient failures are retried (with backoff); permanent ones, such as a missing repository, would fail again. */
fun SyncResult.toWorkOutcome(): WorkOutcome = when {
    this is SyncResult.Failed && retryable -> WorkOutcome.Retry
    this is SyncResult.Failed -> WorkOutcome.Failure
    else -> WorkOutcome.Success
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KanjiApp
        return app.contentSync.sync(app.settings.source.value).toWorkOutcome().let {
            when (it) { WorkOutcome.Success -> Result.success(); WorkOutcome.Retry -> Result.retry(); WorkOutcome.Failure -> Result.failure() }
        }
    }

    companion object {
        private const val PERIODIC = "content-sync-periodic"
        private const val MANUAL = "content-sync-manual"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
