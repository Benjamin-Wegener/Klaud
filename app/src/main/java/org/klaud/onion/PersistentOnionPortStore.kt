package org.klaud.onion

import android.content.Context
import android.content.SharedPreferences
import java.util.*

class PersistentOnionPortStore(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("klaud_onion_port_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_ONION_PORT = "persistent_onion_port"
        private const val KEY_LOCAL_PORT = "persistent_local_port"
        private const val KEY_SOCKS_PORT = "persistent_socks_port"
        private const val KEY_CONTROL_PORT = "persistent_control_port"
        private const val MIN_PORT = 10001
        private const val MAX_PORT = 65535
    }
    
    fun getOrCreateOnionPort(): Int {
        var port = prefs.getInt(KEY_ONION_PORT, -1)
        if (port <= 0) {
            port = generateRandomPort()
            prefs.edit().putInt(KEY_ONION_PORT, port).apply()
        }
        return port
    }

    fun getOrCreateLocalPort(): Int {
        var port = prefs.getInt(KEY_LOCAL_PORT, -1)
        if (port <= 0) {
            port = findAvailableLocalPort()
            prefs.edit().putInt(KEY_LOCAL_PORT, port).apply()
        }
        return port
    }

    fun getOrCreateSocksPort(): Int {
        var port = prefs.getInt(KEY_SOCKS_PORT, -1)
        if (port <= 0) {
            port = findAvailableLocalPort()
            prefs.edit().putInt(KEY_SOCKS_PORT, port).apply()
        }
        return port
    }

    fun getOrCreateControlPort(): Int {
        var port = prefs.getInt(KEY_CONTROL_PORT, -1)
        if (port <= 0) {
            port = findAvailableLocalPort()
            prefs.edit().putInt(KEY_CONTROL_PORT, port).apply()
        }
        return port
    }
    
    private fun generateRandomPort(): Int {
        val random = Random()
        return MIN_PORT + random.nextInt(MAX_PORT - MIN_PORT + 1)
    }

    private fun findAvailableLocalPort(): Int {
        try {
            val serverSocket = java.net.ServerSocket(0)
            val port = serverSocket.localPort
            serverSocket.close()
            return port
        } catch (e: java.io.IOException) {
            return 10000 + Random().nextInt(10000)
        }
    }
    
    fun reset() {
        prefs.edit().clear().apply()
    }
}
