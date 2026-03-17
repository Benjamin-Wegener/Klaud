package org.klaud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.klaud.crypto.CryptoSession
import org.klaud.crypto.HandshakeHandler
import org.klaud.onion.PersistentOnionPortStore
import org.klaud.onion.TorHiddenService
import org.klaud.onion.TorManager
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

class FileSyncService : Service() {

    private val binder = LocalBinder()
    private var serverSocket: ServerSocket? = null
    private var isServerRunning = false
    private var currentServerPort = -1
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private val transferSemaphore = Semaphore(5)

    companion object {
        private const val TAG = "FileSyncService"
        private val NOTIFICATION_ID = TorHiddenService.NOTIFICATION_ID
        private val CHANNEL_ID = TorHiddenService.CHANNEL_ID
        private const val WAKELOCK_TAG = "klaud:filesync_transfer"

        private const val FRAME_TYPE_MANIFEST_REQ: Byte = 0x01
        private const val FRAME_TYPE_MANIFEST_RES: Byte = 0x02
        private const val FRAME_TYPE_FILE_TRANSFER: Byte = 0x03
        private const val FRAME_TYPE_PEER_EXCHANGE_REQ: Byte = 0x04
        private const val FRAME_TYPE_PEER_EXCHANGE_RES: Byte = 0x05
        private const val FRAME_TYPE_DELETE: Byte = 0x06

        private val gson = Gson()

        data class PeerInfo(
            val onionAddress: String,
            val port: Int,
            val name: String,
            val publicKeyHash: String? = null
        )

        data class FileManifestEntry(
            val fileName: String,
            val sha256: String,
            val sizeBytes: Long
        )

        suspend fun sendFileToOnion(
            onionAddress: String,
            port: Int,
            relativePath: String,
            file: File,
            socksPort: Int,
            context: Context
        ): Boolean = withContext(Dispatchers.IO) {
            // isOnline-Check entfernt: TCP-Verbindung entscheidet selbst ob erreichbar
            var rawSocket: Socket? = null
            var session: CryptoSession? = null

            val pm = context.getSystemService(POWER_SERVICE) as? PowerManager
            val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.also {
                it.acquire(10 * 60 * 1000L)
            }

            try {
                val fileSize = file.length()
                val localSha256 = FileRepository.computeSha256Cached(file) ?: return@withContext false

                rawSocket = NetworkManager.connectToOnionAddressSync(
                    onionAddress = onionAddress,
                    port         = port,
                    socksPort    = socksPort,
                    timeoutMs    = 45_000
                ) ?: run {
                    val device = DeviceManager.getDeviceByOnion(onionAddress)
                    device?.let { DeviceManager.updateOnlineStatus(it.id, false) }
                    return@withContext false
                }

                session = try {
                    val peer = DeviceManager.getDeviceByOnion(onionAddress)
                    HandshakeHandler.performClientHandshake(rawSocket, peer?.publicKeyHash)
                } catch (e: Exception) {
                    rawSocket.closeQuietly()
                    return@withContext false
                }

                val myPeers = DeviceManager.getAllDevices().map { PeerInfo(it.onionAddress, it.port, it.name, it.publicKeyHash) }
                val peerExchangePayload = ByteArrayOutputStream().apply {
                    write(FRAME_TYPE_PEER_EXCHANGE_REQ.toInt())
                    write(gson.toJson(myPeers).toByteArray())
                }.toByteArray()
                session.sendFrame(peerExchangePayload)

                val peerResFrame = session.receiveFrame()
                if (peerResFrame.isNotEmpty() && peerResFrame[0] == FRAME_TYPE_PEER_EXCHANGE_RES) {
                    val remotePeersJson = String(peerResFrame, 1, peerResFrame.size - 1)
                    val type = object : TypeToken<List<PeerInfo>>() {}.type
                    val remotePeers: List<PeerInfo> = gson.fromJson(remotePeersJson, type)
                    remotePeers.forEach { peer ->
                        if (peer.onionAddress != TorManager.getOnionHostname()) {
                            CoroutineScope(Dispatchers.IO).launch { 
                                DeviceManager.addDevice(peer.onionAddress, peer.port, peer.name, peer.publicKeyHash) 
                            }
                        }
                    }
                }

                session.sendFrame(byteArrayOf(FRAME_TYPE_MANIFEST_REQ))
                
                val manifestResFrame = session.receiveFrame()
                if (manifestResFrame.isEmpty() || manifestResFrame[0] != FRAME_TYPE_MANIFEST_RES) {
                    return@withContext false
                }

                val manifestJson = String(manifestResFrame, 1, manifestResFrame.size - 1)
                val type = object : TypeToken<List<FileManifestEntry>>() {}.type
                val remoteManifest: List<FileManifestEntry> = gson.fromJson(manifestJson, type)

                val alreadyOnRemote = remoteManifest.any { it.fileName == relativePath && it.sha256 == localSha256 }
                if (alreadyOnRemote) {
                    DeviceManager.updateSyncTimestamp(onionAddress)
                    return@withContext true 
                }

                val headerBytes = ByteArrayOutputStream().also { baos ->
                    DataOutputStream(baos).apply {
                        writeByte(FRAME_TYPE_FILE_TRANSFER.toInt())
                        writeUTF(relativePath)
                        writeLong(fileSize)
                        writeUTF(localSha256)
                        writeLong(file.lastModified())
                        flush()
                    }
                }.toByteArray()
                session.sendFrame(headerBytes)

                file.inputStream().use { inputStream ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                        session.sendFrame(chunk)
                    }
                }

                session.sendFrame(ByteArray(0))

                val responseFrame = session.receiveFrame()
                val response = String(responseFrame)
                if (response == "OK" || response == "SKIP") {
                    DeviceManager.updateSyncTimestamp(onionAddress)
                    return@withContext true
                }
                return@withContext false

            } catch (e: Exception) {
                Log.e(TAG, "Error sending to $onionAddress", e)
                return@withContext false
            } finally {
                session?.close()
                rawSocket?.closeQuietly()
                wl?.release()
            }
        }

        suspend fun sendDeletionToOnion(
            onionAddress: String,
            port: Int,
            relativePath: String,
            socksPort: Int,
            context: Context
        ): Boolean = withContext(Dispatchers.IO) {
            // isOnline-Check entfernt: TCP-Verbindung entscheidet selbst
            var rawSocket: Socket? = null
            var session: CryptoSession? = null
            try {
                rawSocket = NetworkManager.connectToOnionAddressSync(
                    onionAddress = onionAddress, port = port,
                    socksPort = socksPort, timeoutMs = 45_000
                ) ?: run {
                    val device = DeviceManager.getDeviceByOnion(onionAddress)
                    device?.let { DeviceManager.updateOnlineStatus(it.id, false) }
                    return@withContext false
                }
                val peer = DeviceManager.getDeviceByOnion(onionAddress)
                session = HandshakeHandler.performClientHandshake(rawSocket, peer?.publicKeyHash)
                val payload = ByteArrayOutputStream().apply {
                    write(FRAME_TYPE_DELETE.toInt())
                    write(relativePath.toByteArray())
                }.toByteArray()
                session.sendFrame(payload)
                val response = String(session.receiveFrame())
                response == "OK"
            } catch (e: Exception) {
                Log.e(TAG, "Error sending deletion to $onionAddress", e)
                false
            } finally {
                session?.close()
                rawSocket?.closeQuietly()
            }
        }

        private fun Socket.closeQuietly() {
            try { close() } catch (_: IOException) {}
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): FileSyncService = this@FileSyncService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKELOCK_TAG:server")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var port = intent?.getIntExtra("LOCAL_PORT", 0) ?: 0
        if (port == 0) {
            port = PersistentOnionPortStore(this).getOrCreateLocalPort()
        }

        startForeground(NOTIFICATION_ID, buildSharedNotification("Ready on port $port"))
        startFileServer(port)
        return START_STICKY
    }

    private fun startFileServer(port: Int) {
        if (isServerRunning && currentServerPort == port) return
        isServerRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                isServerRunning = true
                currentServerPort = port
                while (isServerRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleClient(clientSocket)
                }
            } catch (e: Exception) {
                if (isServerRunning) Log.e(TAG, "Error in server loop", e)
            } finally {
                isServerRunning = false
            }
        }
    }

    private fun handleClient(rawSocket: Socket) {
        scope.launch {
            transferSemaphore.acquire()
            val wl = wakeLock?.also { it.acquire(10 * 60 * 1000L) }
            var session: CryptoSession? = null

            try {
                val (cryptoSession, senderOnion) = HandshakeHandler.performServerHandshake(rawSocket)
                session = cryptoSession

                while (true) {
                    val firstFrame = session.receiveFrame()
                    if (firstFrame.isEmpty()) break

                    when (val type = firstFrame[0]) {
                        FRAME_TYPE_PEER_EXCHANGE_REQ -> {
                            val remotePeersJson = String(firstFrame, 1, firstFrame.size - 1)
                            val typeToken = object : TypeToken<List<PeerInfo>>() {}.type
                            val remotePeers: List<PeerInfo> = try { gson.fromJson(remotePeersJson, typeToken) } catch (e: Exception) { emptyList() }
                            remotePeers.forEach { peer ->
                                if (peer.onionAddress != TorManager.getOnionHostname()) {
                                    launch { DeviceManager.addDevice(peer.onionAddress, peer.port, peer.name, peer.publicKeyHash) }
                                }
                            }

                            val myPeers = DeviceManager.getAllDevices().map { PeerInfo(it.onionAddress, it.port, it.name, it.publicKeyHash) }
                            val res = ByteArrayOutputStream().apply {
                                write(FRAME_TYPE_PEER_EXCHANGE_RES.toInt())
                                write(gson.toJson(myPeers).toByteArray())
                            }.toByteArray()
                            session.sendFrame(res)
                        }

                        FRAME_TYPE_MANIFEST_REQ -> {
                            val manifest = FileRepository.listFilesRecursive().map {
                                FileManifestEntry(it.relativePath, FileRepository.computeSha256Cached(it.file) ?: "", it.file.length())
                            }
                            val res = ByteArrayOutputStream().apply {
                                write(FRAME_TYPE_MANIFEST_RES.toInt())
                                write(gson.toJson(manifest).toByteArray())
                            }.toByteArray()
                            session.sendFrame(res)
                        }

                        FRAME_TYPE_FILE_TRANSFER -> {
                            val dataIn = DataInputStream(ByteArrayInputStream(firstFrame, 1, firstFrame.size - 1))
                            val relativePath = dataIn.readUTF()
                            val fileSize = dataIn.readLong()
                            val expectedSha256 = dataIn.readUTF()
                            val remoteTimestamp = dataIn.readLong()

                            if (!StorageHelper.hasEnoughSpace(fileSize)) {
                                while (true) { val chunk = session.receiveFrame(); if (chunk.isEmpty()) break }
                                session.sendFrame("ERROR_NO_SPACE".toByteArray())
                                Log.w(TAG, "Not enough space for $relativePath ($fileSize bytes)")
                                continue
                            }

                            val localFile = FileRepository.getFileByRelativePath(relativePath)
                            if (localFile.exists() && localFile.lastModified() > remoteTimestamp) {
                                session.sendFrame("SKIP".toByteArray())
                                continue
                            }

                            val tempFile = File(localFile.parentFile, "${localFile.name}.part")
                            tempFile.parentFile?.mkdirs()

                            val digest = MessageDigest.getInstance("SHA-256")
                            tempFile.outputStream().use { outputStream ->
                                while (true) {
                                    val chunk = session.receiveFrame()
                                    if (chunk.isEmpty()) break
                                    outputStream.write(chunk)
                                    digest.update(chunk)
                                }
                            }

                            if (digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256) {
                                if (localFile.exists()) localFile.delete()
                                tempFile.renameTo(localFile)
                                localFile.setLastModified(remoteTimestamp)
                                SyncContentObserver.markAsReceived(relativePath, senderOnion)
                                Log.i(TAG, "✓ Received: $relativePath")
                                session.sendFrame("OK".toByteArray())
                            } else {
                                tempFile.delete()
                                session.sendFrame("ERROR".toByteArray())
                            }
                        }

                        FRAME_TYPE_DELETE -> {
                            val relativePath = String(firstFrame, 1, firstFrame.size - 1)
                            val deleted = FileRepository.deleteFile(relativePath)
                            Log.i(TAG, "Deleted: $relativePath (success=$deleted)")
                            SyncContentObserver.markAsReceived(relativePath, senderOnion)
                            session.sendFrame("OK".toByteArray())
                        }
                    }
                }
            } catch (e: EOFException) {
            } catch (e: Exception) {
                Log.e(TAG, "Error processing client", e)
            } finally {
                session?.close()
                rawSocket.closeQuietly()
                wl?.release()
                transferSemaphore.release()
            }
        }
    }

    private fun Socket.closeQuietly() {
        try { close() } catch (_: IOException) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: IOException) {}
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildSharedNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Klaud")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
