package org.klaud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val onionAddress: String,
    val port: Int,
    val publicKeyHash: String? = null,
    val pairedAt: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var lastSync: Long = 0,
    var isOnline: Boolean = false
) {
    val shortAddress: String
        get() = "${onionAddress.take(8)}...${onionAddress.takeLast(6)}"
}

object DeviceManager {
    private const val TAG = "DeviceManager"
    private const val PREFS_NAME = "klauddevices"
    private const val DEVICES_KEY = "paired_devices"

    private lateinit var prefs: SharedPreferences
    private lateinit var gson: Gson
    
    private val devices = Collections.synchronizedList(mutableListOf<Device>())
    private val listeners = Collections.synchronizedList(mutableListOf<() -> Unit>())

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        gson = Gson()
        loadDevices()
    }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val currentListeners = synchronized(listeners) { listeners.toList() }
        currentListeners.forEach { it() }
    }

    private fun loadDevices() {
        val devicesJson = prefs.getString(DEVICES_KEY, "[]")
        val type = object : TypeToken<List<Device>>() {}.type
        val loadedDevices: List<Device> = try {
            gson.fromJson(devicesJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading devices", e)
            emptyList()
        }
        synchronized(devices) {
            devices.clear()
            devices.addAll(loadedDevices)
        }
    }

    private fun saveDevices() {
        val devicesJson = synchronized(devices) { gson.toJson(devices) }
        prefs.edit().putString(DEVICES_KEY, devicesJson).apply()
        notifyListeners()
    }

    suspend fun addDevice(onionAddress: String, port: Int, deviceName: String, publicKeyHash: String? = null): Boolean = withContext(Dispatchers.IO) {
        val exists = synchronized(devices) { devices.any { it.onionAddress == onionAddress } }
        if (exists) return@withContext false

        val device = Device(name = deviceName, onionAddress = onionAddress, port = port, publicKeyHash = publicKeyHash)
        devices.add(device)
        saveDevices()
        return@withContext true
    }

    suspend fun removeDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val removed = synchronized(devices) {
            val device = devices.find { it.id == deviceId }
            if (device != null) {
                devices.remove(device)
                true
            } else {
                false
            }
        }
        if (removed) {
            saveDevices()
        }
        return@withContext removed
    }

    fun updateOnlineStatus(deviceId: String, isOnline: Boolean) {
        val device = synchronized(devices) { devices.find { it.id == deviceId } }
        if (device != null) {
            val changed = device.isOnline != isOnline
            device.isOnline = isOnline
            if (isOnline) device.lastSeen = System.currentTimeMillis()
            if (changed) saveDevices() else notifyListeners()
        }
    }

    fun updateSyncTimestamp(onionAddress: String) {
        val device = synchronized(devices) { devices.find { it.onionAddress == onionAddress } }
        if (device != null) {
            device.lastSync = System.currentTimeMillis()
            device.lastSeen = System.currentTimeMillis()
            device.isOnline = true
            saveDevices()
        }
    }

    fun getAllDevices(): List<Device> = synchronized(devices) { devices.toList() }
    fun getSyncTargets(): List<Device> = synchronized(devices) { devices.filter { it.isOnline } }
    fun getDeviceByOnion(onionAddress: String): Device? = synchronized(devices) { devices.find { it.onionAddress == onionAddress } }

    fun isOnline(onionAddress: String): Boolean {
        return getDeviceByOnion(onionAddress)?.isOnline ?: false
    }
}
