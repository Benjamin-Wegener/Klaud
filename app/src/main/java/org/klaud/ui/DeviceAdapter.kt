package org.klaud.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.klaud.Device
import org.klaud.DeviceManager
import org.klaud.NetworkManager
import org.klaud.R
import org.klaud.onion.TorManager

class DeviceAdapter(
    private val onDelete: (Device) -> Unit = {}
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private var devices = listOf<Device>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun updateDevices(newDevices: List<Device>) {
        devices = newDevices
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    fun clear() {
        adapterScope.cancel()
    }

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val deviceNameText: TextView = itemView.findViewById(R.id.device_name_text)
        private val deviceAddressText: TextView = itemView.findViewById(R.id.device_address_text)
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val deleteButton: Button = itemView.findViewById(R.id.delete_button)
        private val testButton: Button = itemView.findViewById(R.id.test_button)

        fun bind(device: Device) {
            deviceNameText.text = device.name
            deviceAddressText.text = device.shortAddress

            val status = if (device.isOnline) {
                "✓ Online (${formatTimeAgo(device.lastSeen)})"
            } else {
                "✗ Offline (${formatTimeAgo(device.lastSeen)})"
            }
            statusText.text = status
            statusText.setTextColor(if (device.isOnline) Color.parseColor("#2E7D32") else Color.GRAY)

            deleteButton.setOnClickListener {
                adapterScope.launch {
                    DeviceManager.removeDevice(device.id)
                    onDelete(device)
                    updateDevices(DeviceManager.getAllDevices())
                }
            }

            testButton.setOnClickListener {
                testTorConnectivity(device)
            }
        }

        private fun testTorConnectivity(device: Device) {
            adapterScope.launch {
                try {
                    testButton.text = "Testing..."
                    testButton.isEnabled = false

                    val socksPort = TorManager.getSocksPort()
                    val isConnected = if (socksPort != null) {
                        withContext(Dispatchers.IO) {
                            NetworkManager.testTorConnectivity(
                                onionAddress = device.onionAddress,
                                port = device.port,
                                socksPort = socksPort
                            )
                        }
                    } else false

                    if (isConnected) {
                        testButton.text = "✓ Success"
                        testButton.setBackgroundColor(Color.parseColor("#2E7D32"))
                        DeviceManager.updateOnlineStatus(device.id, true)
                    } else {
                        testButton.text = "✗ Failed"
                        testButton.setBackgroundColor(Color.parseColor("#C62828"))
                        DeviceManager.updateOnlineStatus(device.id, false)
                    }
                    
                    updateDevices(DeviceManager.getAllDevices())

                    delay(2000)
                    testButton.text = "Test Tor"
                    testButton.setBackgroundColor(Color.LTGRAY)
                    testButton.isEnabled = true

                } catch (e: Exception) {
                    testButton.text = "Error"
                    testButton.isEnabled = true
                }
            }
        }

        private fun formatTimeAgo(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            if (timestamp == 0L) return "Never"
            val minutes = diff / (1000 * 60)
            val hours = diff / (1000 * 60 * 60)
            val days = diff / (1000 * 60 * 60 * 24)

            return when {
                days > 0 -> "${days}d ago"
                hours > 0 -> "${hours}h ago"
                minutes > 0 -> "${minutes}m ago"
                else -> "Just now"
            }
        }
    }
}
