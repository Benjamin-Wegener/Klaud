package org.klaud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.klaud.onion.TorManager

/**
 * Monitor für den Online-Status von Geräten.
 * Prüft regelmäßig alle Geräte und löst Pending-Relay-Queue bei Wiedererkennung.
 */
class DeviceStatusMonitor(
    private val torManager: TorManager,
    private val scope: CoroutineScope,
    private val context: Context
) {
    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val PING_TIMEOUT_MS = 25_000
        private const val TAG = "DeviceStatusMonitor"
    }

    private var job: Job? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                checkAllDevices()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun checkAllDevices() {
        val socksPort = torManager.getSocksPort() ?: run {
            Log.d(TAG, "Tor not ready or network offline, skipping status check")
            return
        }

        val devices = DeviceManager.getAllDevices()
        if (devices.isEmpty()) return

        Log.d(TAG, "Checking ${devices.size} device(s)...")

        devices.forEach { device ->
            val isOnline = pingDevice(device, socksPort)

            val wasOffline = !device.isOnline
            DeviceManager.updateOnlineStatus(device.id, isOnline)

            if (isOnline && wasOffline) {
                val pending = PendingRelayQueue.drainForDevice(device.id)
                if (pending.isNotEmpty()) {
                    Log.i(TAG, "${device.name} came online — flushing ${pending.size} pending relays")
                    scope.launch(Dispatchers.IO) {
                        val currentSocksPort = torManager.getSocksPort() ?: return@launch
                        pending.forEach { entry ->
                            if (entry.startsWith("DEL:")) {
                                val relativePath = entry.removePrefix("DEL:")
                                FileSyncService.sendDeletionToOnion(
                                    onionAddress = device.onionAddress,
                                    port = device.port,
                                    relativePath = relativePath,
                                    socksPort = currentSocksPort,
                                    context = context
                                )
                            } else {
                                val file = FileRepository.getFileByRelativePath(entry)
                                if (file.exists()) {
                                    FileSyncService.sendFileToOnion(
                                        onionAddress = device.onionAddress,
                                        port = device.port,
                                        relativePath = entry,
                                        file = file,
                                        socksPort = currentSocksPort,
                                        context = context
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pingDevice(device: Device, socksPort: Int): Boolean {
        return try {
            val socket = NetworkManager.connectToOnionAddressSync(
                onionAddress = device.onionAddress,
                port = device.port,
                socksPort = socksPort,
                timeoutMs = PING_TIMEOUT_MS
            )
            val isConnected = socket?.isConnected == true
            socket?.close()
            isConnected
        } catch (e: Exception) {
            false
        }
    }
}
