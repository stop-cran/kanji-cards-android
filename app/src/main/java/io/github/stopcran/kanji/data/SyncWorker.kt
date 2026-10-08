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

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as KanjiApp
        return when (val r = app.contentSync.sync(app.settings.source.value)) {
            is SyncResult.Failed -> if (r.retryable) Result.retry() else Result.failure()
            else -> Result.success()
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
