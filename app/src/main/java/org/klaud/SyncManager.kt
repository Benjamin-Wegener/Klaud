package org.klaud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.klaud.onion.TorManager

object SyncManager {
    private const val TAG = "SyncManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun triggerFullSync(context: Context) {
        scope.launch {
            Log.i(TAG, "Manual full sync triggered")
            val socksPort = TorManager.getSocksPort()
            if (socksPort == null) {
                Log.w(TAG, "Tor not ready, cannot sync")
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Tor not ready", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val files = FileRepository.listFilesRecursive()
            val allDevices = DeviceManager.getAllDevices()

            if (allDevices.isEmpty()) {
                Log.d(TAG, "No devices found")
                return@launch
            }

            Log.i(TAG, "Triggering full sync for ${files.size} files to ${allDevices.size} devices")
            
            allDevices.forEach { device ->
                files.forEach { syncFile ->
                    launch {
                        val success = FileSyncService.sendFileToOnion(
                            onionAddress = device.onionAddress,
                            port = device.port,
                            relativePath = syncFile.relativePath,
                            file = syncFile.file,
                            socksPort = socksPort,
                            context = context
                        )
                        if (!success) {
                            Log.d(TAG, "Sync failed for ${syncFile.relativePath} to ${device.name}, adding to queue")
                            PendingRelayQueue.add(device.id, syncFile.relativePath)
                        } else {
                            DeviceManager.updateOnlineStatus(device.id, true)
                        }
                    }
                }
            }
        }
    }
}
