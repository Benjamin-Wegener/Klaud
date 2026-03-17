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
    private const val REMOVED_DEVICES_KEY = "removed_devices"

    private lateinit var prefs: SharedPreferences
    private lateinit var gson: Gson
    
    private val devices = Collections.synchronizedList(mutableListOf<Device>())
    private val removedDevices = Collections.synchronizedSet(mutableSetOf<String>())
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
        val removedJson = prefs.getString(REMOVED_DEVICES_KEY, "[]")
        val type = object : TypeToken<List<Device>>() {}.type
        val setType = object : TypeToken<Set<String>>() {}.type
        
        val loadedDevices: List<Device> = try {
            gson.fromJson(devicesJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading devices", e)
            emptyList()
        }
        
        val loadedRemoved: Set<String> = try {
            gson.fromJson(removedJson, setType) ?: emptySet()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading removed devices", e)
            emptySet()
        }
        
        synchronized(devices) {
            devices.clear()
            devices.addAll(loadedDevices)
        }
        synchronized(removedDevices) {
            removedDevices.clear()
            removedDevices.addAll(loadedRemoved)
        }
    }

    private fun saveDevices() {
        val devicesJson = synchronized(devices) { gson.toJson(devices) }
        val removedJson = synchronized(removedDevices) { gson.toJson(removedDevices) }
        prefs.edit()
            .putString(DEVICES_KEY, devicesJson)
            .putString(REMOVED_DEVICES_KEY, removedJson)
            .apply()
        notifyListeners()
    }

    suspend fun addDevice(onionAddress: String, port: Int, deviceName: String, publicKeyHash: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (synchronized(removedDevices) { removedDevices.contains(onionAddress) }) {
            Log.d(TAG, "Ignoring device $onionAddress as it was previously removed")
            return@withContext false
        }
        
        val exists = synchronized(devices) { devices.any { it.onionAddress == onionAddress } }
        if (exists) return@withContext false

        val device = Device(name = deviceName, onionAddress = onionAddress, port = port, publicKeyHash = publicKeyHash)
        devices.add(device)
        saveDevices()
        return@withContext true
    }

    suspend fun removeDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        var onionToMark: String? = null
        val removed = synchronized(devices) {
            val device = devices.find { it.id == deviceId }
            if (device != null) {
                onionToMark = device.onionAddress
                devices.remove(device)
                true
            } else {
                false
            }
        }
        if (removed && onionToMark != null) {
            synchronized(removedDevices) {
                removedDevices.add(onionToMark!!)
            }
            saveDevices()
        }
        return@withContext removed
    }

    fun clearRemovedDevices() {
        synchronized(removedDevices) {
            removedDevices.clear()
        }
        saveDevices()
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
