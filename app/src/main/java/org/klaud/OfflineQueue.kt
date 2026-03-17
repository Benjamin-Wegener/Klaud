package org.klaud

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

class OfflineQueue(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("offline_queue", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class QueueItem(val relativePath: String, val onionAddress: String, val isDeletion: Boolean = false)

    fun enqueue(relativePath: String, onionAddress: String, isDeletion: Boolean = false) {
        val items = getItems().toMutableList()
        if (items.any { it.relativePath == relativePath && it.onionAddress == onionAddress && it.isDeletion == isDeletion }) return
        items.add(QueueItem(relativePath, onionAddress, isDeletion))
        saveItems(items)
    }

    fun getItems(): List<QueueItem> {
        val json = prefs.getString("items", "[]")
        val type = object : TypeToken<List<QueueItem>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    private fun saveItems(items: List<QueueItem>) {
        prefs.edit().putString("items", gson.toJson(items)).apply()
    }

    fun remove(item: QueueItem) {
        val items = getItems().toMutableList()
        items.remove(item)
        saveItems(items)
    }

    fun clear() {
        prefs.edit().remove("items").apply()
    }
}
