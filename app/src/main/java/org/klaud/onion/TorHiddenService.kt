package org.klaud.onion

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import net.freehaven.tor.control.TorControlConnection
import org.klaud.FileSyncService
import org.klaud.R
import org.klaud.crypto.KyberKeyManager
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class TorHiddenService : Service() {
    private val binder = LocalBinder()
    private var isRunning = false
    private var isStarting = false
    private var onionAddress: String? = null
    private var onionPort: Int = 0
    private var localPort: Int = 0
    private var torProcess: Process? = null

    @Volatile
    private var activeSocksPort: Int? = null

    private var networkAvailable = false
    private var lastSuccessfulConnect = 0L
    private lateinit var connectivityManager: ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkAvailable = true
            broadcastLog("[TOR] Network available")
            scope.launch {
                if (isRunning) {
                    val success = authenticateAndSetDisableNetwork(false)
                    if (!success || (System.currentTimeMillis() - lastSuccessfulConnect > 120_000L)) {
                        restartTorCircuits()
                    }
                } else if (!isStarting) {
                    startRealTor()
                }
            }
        }

        override fun onLost(network: Network) {
            networkAvailable = false
            broadcastLog("[TOR] Network lost — pause Tor via ControlPort")
            scope.launch { authenticateAndSetDisableNetwork(true) }
        }
    }

    private val torDataDir by lazy { File(filesDir, "tordata") }
    private val hiddenServiceDir by lazy { File(torDataDir, "hidden_service") }
    private val hostnameFile by lazy { File(hiddenServiceDir, "hostname") }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "TorHiddenService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "klaud_service"
        private const val MAX_WAIT_SECONDS = 180

        const val ACTION_TOR_LOG = "org.klaud.TOR_LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
    }

    inner class LocalBinder : Binder() {
        fun getService(): TorHiddenService = this@TorHiddenService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        torDataDir.mkdirs()
        hiddenServiceDir.mkdirs()
        
        hiddenServiceDir.setReadable(true, true)
        hiddenServiceDir.setWritable(true, true)
        hiddenServiceDir.setExecutable(true, true)
        
        torDataDir.setReadable(true, true)
        torDataDir.setWritable(true, true)
        torDataDir.setExecutable(true, true)
        
        createNotificationChannel()

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        networkAvailable = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning && onionAddress != null) return START_STICKY
        if (isStarting) return START_STICKY

        val store = PersistentOnionPortStore(this)
        localPort = intent?.getIntExtra("LOCAL_PORT", 0) ?: 0
        if (localPort == 0) {
            localPort = store.getOrCreateLocalPort()
        }
        onionPort = store.getOrCreateOnionPort()

        if (torProcess == null) {
            startForeground(NOTIFICATION_ID, createNotification("Starting Tor..."))
            scope.launch { startRealTor() }
        }
        
        return START_STICKY
    }

    private suspend fun startRealTor() = withContext(Dispatchers.IO) {
        if (isStarting) return@withContext
        isStarting = true
        try {
            broadcastLog("Starting REAL Tor...")

            val torrcFile = File(torDataDir, "torrc")
            val torrcConfig = createTorrcConfig(onionPort, localPort)
            torrcFile.writeText(torrcConfig)

            val executable = findTorBinary()
            if (executable == null) {
                broadcastLog("[TOR ERROR] Could not find or extract Tor binary")
                return@withContext
            }

            startTorProcess(executable, torrcFile)

            val logJob = scope.launch {
                readTorOutput()
            }

            val success = waitForOnionAddress()
            if (success) {
                if (confirmSocksPortListening()) {
                    isRunning = true
                    lastSuccessfulConnect = System.currentTimeMillis()
                    
                    writePairingJson(onionAddress!!, KyberKeyManager.getPublicKeyHash())

                    broadcastLog("Real Tor hidden service ready: $onionAddress:$onionPort")
                    updateNotification("Connected: ${onionAddress?.take(16)}...:$onionPort")

                    val store = PersistentOnionPortStore(this@TorHiddenService)
                    val lp = store.getOrCreateLocalPort()
                    val fsIntent = Intent(this@TorHiddenService, FileSyncService::class.java).apply {
                        putExtra("LOCAL_PORT", lp)
                    }
                    startService(fsIntent)
                }
            } else {
                broadcastLog("[TOR ERROR] Failed to get .onion address after $MAX_WAIT_SECONDS seconds")
            }
        } catch (e: Exception) {
            broadcastLog("[TOR ERROR] Error starting real Tor: ${e.message}")
            isRunning = false
        } finally {
            isStarting = false
        }
    }

    private fun writePairingJson(onion: String, keyHash: String) {
        if (!onion.endsWith(".onion") || onion == "pending.onion") return
        try {
            val json = """{"onion":"$onion","port":$onionPort,"key":"$keyHash"}"""
            val file = File(getExternalFilesDir(null), "pairing.json")
            file.writeText(json)
            Log.i(TAG, "pairing.json written: $onion")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write pairing.json", e)
        }
    }

    private fun findTorBinary(): File? {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val names = arrayOf("libtor.so", "tor", "libtor")
        
        for (name in names) {
            val f = File(nativeLibDir, name)
            if (f.exists()) {
                broadcastLog("Found Tor binary at native path: ${f.absolutePath}")
                return f
            }
        }
        
        // Manual extraction if not found in native library dir (common when extractNativeLibs=false)
        val extractedTor = File(torDataDir, "tor_bin")
        if (extractedTor.exists()) {
            extractedTor.setExecutable(true)
            return extractedTor
        }

        try {
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val zipEntryName = "lib/$abi/libtor.so"
            val apkFile = File(applicationInfo.sourceDir)
            
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(zipEntryName)
                if (entry != null) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(extractedTor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    extractedTor.setExecutable(true)
                    broadcastLog("Extracted Tor binary to: ${extractedTor.absolutePath}")
                    return extractedTor
                }
            }
        } catch (e: Exception) {
            broadcastLog("[TOR ERROR] Failed to extract Tor binary: ${e.message}")
        }
        
        return null
    }

    private fun startTorProcess(executable: File, torrc: File) {
        try {
            torProcess = ProcessBuilder(executable.absolutePath, "-f", torrc.absolutePath)
                .directory(torDataDir)
                .apply { 
                    environment()["HOME"] = torDataDir.absolutePath
                    environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
                }
                .redirectErrorStream(true)
                .start()
            Log.i(TAG, "Tor process started")
        } catch (e: Exception) {
            broadcastLog("[TOR ERROR] ProcessBuilder failed: ${e.message}")
        }
    }

    private suspend fun restartTorCircuits() = withContext(Dispatchers.IO) {
        broadcastLog("[TOR] Network unstable — restarting Tor process...")
        stopTorProcess()
        isRunning = false
        onionAddress = null
        activeSocksPort = null
        delay(2000L)
        startRealTor()
    }

    private suspend fun authenticateAndSetDisableNetwork(disabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val store = PersistentOnionPortStore(this@TorHiddenService)
        val controlPort = store.getOrCreateControlPort()
        val cookieFile = File(torDataDir, "control_auth_cookie")
        
        if (!cookieFile.exists()) return@withContext false

        var socket: Socket? = null
        try {
            socket = Socket("127.0.0.1", controlPort)
            val conn = TorControlConnection(socket)
            conn.authenticate(cookieFile.readBytes())
            
            val valStr = if (disabled) "1" else "0"
            conn.setConf("DisableNetwork", valStr)
            
            if (!disabled) {
                conn.signal("HUP")
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            socket?.close()
        }
    }

    private suspend fun confirmSocksPortListening(): Boolean {
        val port = activeSocksPort ?: return false
        var attempts = 0
        while (attempts < 15) {
            try {
                val socket = ServerSocket(port)
                socket.close()
            } catch (e: Exception) {
                return true
            }
            delay(1000)
            attempts++
        }
        return false
    }

    private fun broadcastLog(message: String) {
        val intent = Intent(ACTION_TOR_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, message)
    }

    private fun readTorOutput() {
        try {
            val process = torProcess ?: return
            val reader = process.inputStream.bufferedReader()
            reader.useLines { lines ->
                lines.forEach { line ->
                    broadcastLog("[TOR] $line")
                }
            }
        } catch (e: Exception) {}
    }

    private suspend fun waitForOnionAddress(): Boolean = withContext(Dispatchers.IO) {
        var attempts = 0
        while (attempts < MAX_WAIT_SECONDS) {
            if (hostnameFile.exists() && hostnameFile.length() > 0) {
                try {
                    val address = hostnameFile.readText().trim()
                    if (address.endsWith(".onion") && address.length == 62) {
                        onionAddress = address
                        return@withContext true
                    }
                } catch (e: Exception) {}
            }
            delay(1000)
            attempts++
        }
        false
    }

    private fun createTorrcConfig(hiddenServicePort: Int, localTargetPort: Int): String {
        require(hiddenServicePort > 0) { "hiddenServicePort must be > 0, got ${hiddenServicePort}" }
        require(localTargetPort > 0) { "localTargetPort must be > 0, got ${localTargetPort}" }

        val store = PersistentOnionPortStore(this)
        val socksPort = store.getOrCreateSocksPort()
        val controlPort = store.getOrCreateControlPort()
        activeSocksPort = socksPort

        return """
            DataDirectory ${torDataDir.absolutePath}
            HiddenServiceDir ${hiddenServiceDir.absolutePath}
            HiddenServicePort $hiddenServicePort 127.0.0.1:$localTargetPort
            HiddenServiceVersion 3
            SocksPort $socksPort
            ControlPort $controlPort
            CookieAuthentication 1
            Log notice stdout
            Log notice file ${torDataDir.absolutePath}/tor.log
        """.trimIndent()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Tor Service",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Klaud Tor Service")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(content))
    }

    fun getOnionAddressOnly(): String? = onionAddress
    fun getOnionPort(): Int = onionPort
    fun getLocalPort(): Int = localPort
    fun isRunning(): Boolean = isRunning
    fun getSocksPort(): Int? = if (networkAvailable) activeSocksPort else null

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        scope.cancel()
        stopTorProcess()
    }

    private fun stopTorProcess() {
        torProcess?.let { process ->
            try {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
                torProcess = null
                activeSocksPort = null
            } catch (e: Exception) {}
        }
    }
}
