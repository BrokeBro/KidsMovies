package com.kidsmovies.app.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.kidsmovies.app.KidsMoviesApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "content_sync_work"
        private const val PERIODIC_WORK_NAME = "periodic_content_sync_work"

        /**
         * Schedule periodic sync every 10 minutes
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                10, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1, TimeUnit.MINUTES
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicWorkRequest
                )

            Log.d(TAG, "Scheduled periodic sync every 10 minutes")
        }

        /**
         * Request an immediate one-time sync
         */
        fun requestImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val oneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    oneTimeWorkRequest
                )

            Log.d(TAG, "Requested immediate sync")
        }

        /**
         * Cancel all sync work
         */
        fun cancelAllSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            Log.d(TAG, "Cancelled all sync work")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting sync work")

        try {
            val app = applicationContext as? KidsMoviesApp
            if (app == null) {
                Log.e(TAG, "Could not get KidsMoviesApp")
                return@withContext Result.failure()
            }

            // Check if device is paired
            val pairingState = app.pairingRepository.getPairingState()
            if (pairingState == null || !pairingState.isPaired) {
                Log.d(TAG, "Not paired, skipping sync")
                return@withContext Result.success()
            }

            // Perform settings sync
            app.settingsSyncManager.forcSync()

            // Perform content sync
            app.contentSyncManager.performFullSync()

            // Check for pending locks
            app.contentSyncManager.checkPendingLocks()

            Log.d(TAG, "Sync work completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync work failed", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
