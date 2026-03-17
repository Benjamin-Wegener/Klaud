package org.klaud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.klaud.onion.TorHiddenService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("BootReceiver", "Device restarted — starting TorHiddenService")
            context.startForegroundService(Intent(context, TorHiddenService::class.java))
        }
    }
}
