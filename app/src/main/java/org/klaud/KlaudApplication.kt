package org.klaud

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.klaud.crypto.KyberKeyManager
import org.klaud.onion.TorHiddenService
import org.klaud.onion.TorManager

class KlaudApplication : Application(), Configuration.Provider {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceStatusMonitor: DeviceStatusMonitor? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

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
            // We bind the service here. On Android 14, starting a foreground service from 
            // Application.onCreate can fail if the app is started from the background.
            // BootReceiver now uses TorStartWorker to handle this safely on boot.
            bindService(intent, torServiceConnection, BIND_AUTO_CREATE)
            Log.i("KlaudApplication", "TorHiddenService bound in Application")
        } catch (e: Exception) {
            Log.e("KlaudApplication", "Failed to bind TorHiddenService", e)
        }
    }

    private val torServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val torService = (service as TorHiddenService.LocalBinder).getService()
            TorManager.setService(torService)
            Log.i("KlaudApplication", "TorHiddenService connected and bound")

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
            Log.w("KlaudApplication", "TorHiddenService disconnected — re-binding")
            scope.launch {
                kotlinx.coroutines.delay(3000)
                startTorService()
            }
        }
    }
}
