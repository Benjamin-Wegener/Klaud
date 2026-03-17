package org.klaud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import org.klaud.onion.TorHiddenService

class TorStartWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        applicationContext.startForegroundService(
            Intent(applicationContext, TorHiddenService::class.java)
        )
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = TorHiddenService.CHANNEL_ID
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Klaud", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("Klaud")
            .setContentText("Starting Tor…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        return ForegroundInfo(TorHiddenService.NOTIFICATION_ID, notification)
    }
}
