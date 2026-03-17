package org.klaud

import android.content.Context
import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.*
import org.klaud.onion.TorManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SyncContentObserver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val torManager: TorManager
) {

    private val observers = ConcurrentHashMap<String, RecursiveFileObserver>()

    companion object {
        private const val TAG = "SyncContentObserver"
        private val recentlyReceivedFrom = ConcurrentHashMap<String, String>()
        private const val RECEIVED_EXPIRY_MS = 90_000L

        private val internalScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        fun markAsReceived(relativePath: String, senderOnion: String?) {
            senderOnion?.let {
                recentlyReceivedFrom[relativePath] = it
                Log.d(TAG, "markAsReceived: $relativePath from $it")
                internalScope.launch {
                    delay(RECEIVED_EXPIRY_MS)
                    recentlyReceivedFrom.remove(relativePath)
                }
            }
        }

        private fun getOriginalSender(relativePath: String): String? {
            return recentlyReceivedFrom[relativePath]
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            val root = FileRepository.getSyncRoot()
            watchRecursive(root)
            Log.d(TAG, "Finished setting up recursive file observers")
        }
    }

    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
    }

    private fun watchRecursive(dir: File) {
        val path = dir.absolutePath
        if (observers.containsKey(path)) return

        try {
            val observer = RecursiveFileObserver(path)
            observer.startWatching()
            observers[path] = observer

            dir.listFiles()?.filter { it.isDirectory }?.forEach { watchRecursive(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error watching $path", e)
        }
    }

    inner class RecursiveFileObserver(private val path: String) : 
        FileObserver(File(path), CREATE or CLOSE_WRITE or MOVED_TO or DELETE or MOVED_FROM) {

        override fun onEvent(event: Int, fileName: String?) {
            if (fileName == null) return

            if (fileName.endsWith(".part")) {
                return
            }

            val file = File(path, fileName)

            if (event and CREATE != 0 && file.isDirectory) {
                scope.launch(Dispatchers.IO) {
                    watchRecursive(file)
                }
            }

            if (event and (CLOSE_WRITE or MOVED_TO) != 0) {
                val relativePath = FileRepository.getRelativePath(file)
                val originalSender = getOriginalSender(relativePath)

                scope.launch(Dispatchers.IO) {
                    onFileChanged(relativePath, originalSender)
                }
            }

            if (event and (DELETE or MOVED_FROM) != 0) {
                val relativePath = FileRepository.getRelativePath(file)
                scope.launch(Dispatchers.IO) { onFileDeletion(relativePath, null) }
            }
        }
    }

    private fun onFileChanged(relativePath: String, excludeOnion: String?) {
        val devices = DeviceManager.getAllDevices()
        val socksPort = torManager.getSocksPort() ?: return
        val file = FileRepository.getFileByRelativePath(relativePath)

        if (!file.exists() || file.isDirectory) {
            return
        }

        if (relativePath.endsWith(".part")) {
            return
        }

        devices.forEach { device ->
            if (device.onionAddress == excludeOnion) {
                return@forEach
            }

            scope.launch(Dispatchers.IO) {
                val success = FileSyncService.sendFileToOnion(
                    onionAddress = device.onionAddress,
                    port = device.port,
                    relativePath = relativePath,
                    file = file,
                    socksPort = socksPort,
                    context = context
                )
                if (!success) {
                    PendingRelayQueue.add(device.id, relativePath)
                }
            }
        }
    }

    private fun onFileDeletion(relativePath: String, excludeOnion: String?) {
        if (getOriginalSender(relativePath) != null) {
            Log.d(TAG, "Skipping deletion echo for $relativePath")
            return
        }

        val devices = DeviceManager.getAllDevices()
        val socksPort = torManager.getSocksPort() ?: return
        devices.forEach { device ->
            if (device.onionAddress == excludeOnion) return@forEach
            
            scope.launch(Dispatchers.IO) {
                val success = FileSyncService.sendDeletionToOnion(
                    onionAddress = device.onionAddress,
                    port = device.port,
                    relativePath = relativePath,
                    socksPort = socksPort,
                    context = context
                )
                if (!success) {
                    PendingRelayQueue.addDeletion(device.id, relativePath)
                }
            }
        }
    }
}
