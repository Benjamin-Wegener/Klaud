package org.klaud

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.klaud.crypto.KyberKeyManager
import org.klaud.onion.TorHiddenService
import org.klaud.onion.TorManager

class KlaudApplication : Application() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceStatusMonitor: DeviceStatusMonitor? = null

    override fun onCreate() {
        super.onCreate()
        FileRepository.initialize(this)
        KyberKeyManager.init(this)
        DeviceManager.initialize(this)
        SyncPreferences.initialize(this)
        PendingRelayQueue.initialize(this)

        startTorService()
    }

    private fun startTorService() {
        try {
            val intent = Intent(this, TorHiddenService::class.java)
            startForegroundService(intent)
            bindService(intent, torServiceConnection, BIND_AUTO_CREATE)
            Log.i("KlaudApplication", "TorHiddenService started")
        } catch (e: Exception) {
            Log.e("KlaudApplication", "Failed to start TorHiddenService", e)
        }
    }

    private val torServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val torService = (service as TorHiddenService.LocalBinder).getService()
            TorManager.setService(torService)
            Log.i("KlaudApplication", "TorHiddenService bound in Application")

            if (deviceStatusMonitor == null) {
                deviceStatusMonitor = DeviceStatusMonitor(TorManager, scope, this@KlaudApplication)
                deviceStatusMonitor?.start()

                // Trigger an initial check immediately
                scope.launch {
                    deviceStatusMonitor?.checkAllDevices()
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            TorManager.setService(null)
            deviceStatusMonitor?.stop()
            deviceStatusMonitor = null
            Log.w("KlaudApplication", "TorHiddenService disconnected — restarting")
            scope.launch {
                kotlinx.coroutines.delay(3000)
                startTorService()
            }
        }
    }
}
