package com.example.screenshotmanager

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Deletes the pCloud copy of a screenshot that was deleted on the phone. Runs whenever there is a
 * network connection; if the copy isn't there yet (it may still be uploading) it keeps looking
 * for a while.
 */
class PcloudDeleteWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val name = inputData.getString(KEY_NAME) ?: return Result.success()
        val deletedAt = inputData.getLong(KEY_DELETED_AT, 0)
        val outcome = Pcloud.deleteByName(applicationContext, name)
        AppLog.i("pCloud delete $name: $outcome (attempt ${runAttemptCount + 1})")
        return when (outcome) {
            Pcloud.Outcome.DELETED, Pcloud.Outcome.NOT_CONNECTED -> Result.success()
            Pcloud.Outcome.NOT_FOUND ->
                if (System.currentTimeMillis() - deletedAt < WAIT_FOR_UPLOAD_MS) Result.retry() else Result.success()
            Pcloud.Outcome.SESSION_EXPIRED -> {
                // Kept until the user logs in again, then retried (see Pcloud.saveLogin).
                Pcloud.addPendingDelete(applicationContext, name)
                ScreenshotNotifier.showPcloudExpired(applicationContext)
                Result.success()
            }
            Pcloud.Outcome.FAILED -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_DELETED_AT = "deleted_at"
        private val WAIT_FOR_UPLOAD_MS = TimeUnit.MINUTES.toMillis(15)
        private const val MAX_ATTEMPTS = 10

        fun enqueue(context: Context, names: List<String>) {
            if (names.isEmpty() || !Pcloud.isConnected(context) || !Prefs.deleteFromPcloud(context)) return
            if (Pcloud.isExpired(context)) {
                names.forEach { Pcloud.addPendingDelete(context, it) }
                ScreenshotNotifier.showPcloudExpired(context)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val now = System.currentTimeMillis()
            val workManager = WorkManager.getInstance(context)
            for (name in names) {
                val request = OneTimeWorkRequestBuilder<PcloudDeleteWorker>()
                    .setInputData(workDataOf(KEY_NAME to name, KEY_DELETED_AT to now))
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 2, TimeUnit.MINUTES)
                    .build()
                workManager.enqueueUniqueWork("pcloud-delete-$name", ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
