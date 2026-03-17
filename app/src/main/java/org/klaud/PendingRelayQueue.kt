package org.klaud

import android.content.Context
import android.content.SharedPreferences

object PendingRelayQueue {
    private lateinit var prefs: SharedPreferences
    
    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("klaud_pending_relay_queue", Context.MODE_PRIVATE)
    }

    fun add(deviceId: String, relativePath: String) {
        val key = "pending_$deviceId"
        val existing = prefs.getStringSet(key, emptySet<String>())?.toMutableSet() ?: mutableSetOf<String>()
        existing.add(relativePath)
        prefs.edit().putStringSet(key, existing).apply()
    }

    fun addDeletion(deviceId: String, relativePath: String) {
        add(deviceId, "DEL:$relativePath")
    }

    fun drainForDevice(deviceId: String): Set<String> {
        val key = "pending_$deviceId"
        val result = prefs.getStringSet(key, emptySet<String>())?.toSet() ?: emptySet<String>()
        prefs.edit().remove(key).apply()
        return result
    }

    fun hasPending(deviceId: String): Boolean {
        return prefs.getStringSet("pending_$deviceId", null)?.isNotEmpty() == true
    }
}
