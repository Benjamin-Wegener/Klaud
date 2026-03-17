package org.klaud

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.klaud.onion.TorManager
import java.util.concurrent.TimeUnit

class ScheduledSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ScheduledSyncWorker"
        const val WORK_NAME = "klaud_periodic_sync"

        fun reschedule(context: Context) {
            val hours = SyncPreferences.getIntervalHours()
            if (hours <= 0) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Periodic sync disabled")
                return
            }

            val request = PeriodicWorkRequestBuilder<ScheduledSyncWorker>(
                hours, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Periodic sync scheduled: every $hours hours")
        }
    }

    override suspend fun doWork(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        if (runAttemptCount > 3) {
            Log.e(TAG, "Too many retries ($runAttemptCount), stopping sync attempt")
            return@withContext ListenableWorker.Result.failure()
        }

        Log.i(TAG, "Scheduled full sync started")

        val socksPort = TorManager.getSocksPort()
        if (socksPort == null) {
            Log.w(TAG, "Tor not ready - retry")
            return@withContext ListenableWorker.Result.retry()
        }

        return@withContext try {
            val allFiles = FileRepository.listFilesRecursive()
            val allDevices = DeviceManager.getAllDevices()

            if (allDevices.isEmpty()) {
                Log.d(TAG, "No devices configured")
                return@withContext ListenableWorker.Result.success()
            }

            val onlineDevices = allDevices.filter { it.isOnline }
            val offlineDevices = allDevices.filter { !it.isOnline }

            if (onlineDevices.isNotEmpty()) {
                Log.i(TAG, "Syncing ${allFiles.size} file(s) to ${onlineDevices.size} online device(s)")
                for (syncFile in allFiles) {
                    for (device in onlineDevices) {
                        try {
                            FileSyncService.sendFileToOnion(
                                onionAddress = device.onionAddress,
                                port = device.port,
                                relativePath = syncFile.relativePath,
                                file = syncFile.file,
                                socksPort = socksPort,
                                context = applicationContext
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error: ${syncFile.relativePath} -> ${device.name}", e)
                        }
                    }
                }
            }

            if (offlineDevices.isNotEmpty()) {
                Log.i(TAG, "Queuing ${allFiles.size} file(s) for ${offlineDevices.size} offline device(s)")
                for (device in offlineDevices) {
                    for (syncFile in allFiles) {
                        PendingRelayQueue.add(device.id, syncFile.relativePath)
                    }
                }
            }

            Log.i(TAG, "Scheduled sync task finished processing devices")
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled sync failed", e)
            ListenableWorker.Result.retry()
        }
    }
}
