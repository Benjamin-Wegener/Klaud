package org.klaud

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.klaud.crypto.HandshakeHandler
import org.klaud.onion.TorManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

object NetworkManager {
    private const val TAG = "NetworkManager"
    private const val DEFAULT_TIMEOUT_MS = 30000

    private fun getActiveSocksPort(): Int {
        return TorManager.getSocksPort()
            ?: error("Tor SOCKS port not available")
    }

    suspend fun connectToOnionAddress(
        onionAddress: String,
        port: Int,
        socksHost: String = "127.0.0.1",
        socksPort: Int = getActiveSocksPort(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Socket? = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.i(TAG, "Connecting to $onionAddress:$port via SOCKS $socksHost:$socksPort")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
            val socket = Socket(proxy)
            socket.connect(InetSocketAddress.createUnresolved(onionAddress, port), timeoutMs)
            
            if (socket.isConnected) {
                Log.i(TAG, "✓ Connected to $onionAddress:$port via Tor")
                socket
            } else {
                Log.e(TAG, "Socket connection failed")
                socket.close()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to onion address $onionAddress:$port", e)
            null
        }
    }

    fun connectToOnionAddressSync(
        onionAddress: String,
        port: Int,
        socksHost: String = "127.0.0.1",
        socksPort: Int,
        timeoutMs: Int = 20_000
    ): Socket? {
        return try {
            Log.d(TAG, "Sync: Connecting to $onionAddress:$port via SOCKS $socksHost:$socksPort")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksHost, socksPort))
            val socket = Socket(proxy)
            socket.connect(InetSocketAddress.createUnresolved(onionAddress, port), timeoutMs)

            if (socket.isConnected) {
                socket
            } else {
                socket.close()
                null
            }
        } catch (e: java.net.SocketException) {
            if (e.message?.contains("SOCKS: Host unreachable") == true) {
                Log.w(TAG, "Onion address unreachable (offline or circuit failed): $onionAddress")
            } else {
                Log.e(TAG, "SocketException during sync connect for $onionAddress: ${e.message}")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Sync connect failed for $onionAddress:$port", e)
            null
        }
    }

    suspend fun testTorConnectivity(
        onionAddress: String,
        port: Int,
        socksHost: String = "127.0.0.1",
        socksPort: Int = getActiveSocksPort()
    ): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null

        try {
            socket = connectToOnionAddress(onionAddress, port, socksHost, socksPort, 15000)
            if (socket != null) {
                Log.i(TAG, "✓ Tor connectivity test successful for $onionAddress:$port")
                return@withContext true
            } else {
                Log.w(TAG, "✗ Tor connectivity test failed for $onionAddress:$port")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tor connectivity test failed: $onionAddress:$port", e)
            return@withContext false
        } finally {
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing test connection", e)
            }
        }
    }

    fun isValidOnionAddress(address: String): Boolean {
        return address.endsWith(".onion") && (address.length == 22 || address.length == 62)
    }

    suspend fun performPairingHandshake(
        context: Context,
        onionAddress: String,
        port: Int,
        expectedPubKeyHash: String,
        deviceName: String
    ): PairingResult = withContext(Dispatchers.IO) {
        val socksPort = TorManager.getSocksPort()
            ?: return@withContext PairingResult.Error("Tor not ready")

        return@withContext try {
            val socket = connectToOnionAddressSync(onionAddress, port, socksPort = socksPort, timeoutMs = 30_000)
            socket?.use {
                HandshakeHandler.performClientHandshake(it, expectedPubKeyHash)

                val added = DeviceManager.addDevice(onionAddress, port, deviceName, expectedPubKeyHash)
                if (added) {
                    SyncManager.triggerFullSync(context)
                    PairingResult.Success(deviceName)
                } else PairingResult.AlreadyPaired
            } ?: PairingResult.Error("Connection failed")
        } catch (e: Exception) {
            PairingResult.Error(e.message ?: "Connection error")
        }
    }
}
