package org.klaud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && 
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
            
        val request = OneTimeWorkRequestBuilder<TorStartWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
            
        WorkManager.getInstance(context)
            .enqueueUniqueWork("tor_boot_start", ExistingWorkPolicy.KEEP, request)
    }
}
