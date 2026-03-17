package org.klaud.onion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*

object TorManager {
    private const val TAG = "TorManager"
    private const val MAX_STARTUP_WAIT_MS = 120_000L // 2 minutes

    private var torService: TorHiddenService? = null
    private var serviceConnection: ServiceConnection? = null
    private var isBound = false

    fun setService(service: TorHiddenService?) {
        this.torService = service
        this.isBound = service != null
        Log.d(TAG, "TorService ${if (service != null) "connected" else "disconnected"} in TorManager")
    }

    fun isTorRunning(): Boolean = torService?.isRunning() == true

    suspend fun startTorAndGetOnion(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val intent = Intent(context, TorHiddenService::class.java)
            context.startService(intent)

            if (!isBound && !bindToTorService(context)) {
                Log.e(TAG, "Failed to bind to TorHiddenService")
                return@withContext null
            }

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < MAX_STARTUP_WAIT_MS) {
                val onion = torService?.getOnionAddressOnly()
                if (onion != null && onion.endsWith(".onion") && onion.length == 62) {
                    Log.i(TAG, "✓ Got real .onion: $onion")
                    return@withContext onion
                }
                delay(1000)
            }

            Log.e(TAG, "Timeout waiting for .onion address")
            null

        } catch (e: Exception) {
            Log.e(TAG, "Error starting Tor", e)
            null
        }
    }

    fun getOnionHostname(): String? {
        return torService?.getOnionAddressOnly()
    }

    fun getOnionPort(): Int? {
        return torService?.getOnionPort()
    }

    private suspend fun bindToTorService(context: Context): Boolean = withContext(Dispatchers.IO) {
        val intent = Intent(context, TorHiddenService::class.java)
        val latch = CompletableDeferred<Boolean>()

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
                torService = (service as TorHiddenService.LocalBinder).getService()
                isBound = true
                Log.d(TAG, "✓ Bound to TorHiddenService")
                latch.complete(true)
            }

            override fun onServiceDisconnected(arg0: ComponentName?) {
                torService = null
                isBound = false
                Log.w(TAG, "Disconnected from TorHiddenService")
            }
        }

        val bound = context.bindService(intent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        if (!bound) {
            latch.complete(false)
        }

        withTimeoutOrNull(5000) {
            latch.await()
        } ?: false
    }

    fun getSocksPort(): Int? {
        return torService?.getSocksPort()
    }

    fun stopTor(context: Context) {
        try {
            if (isBound) {
                serviceConnection?.let { context.unbindService(it) }
                isBound = false
            }
            torService = null

            val intent = Intent(context, TorHiddenService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Tor", e)
        }
    }
}
