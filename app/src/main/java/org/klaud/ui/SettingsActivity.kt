package org.klaud.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.klaud.DeviceManager
import org.klaud.ScheduledSyncWorker
import org.klaud.SyncManager
import org.klaud.SyncPreferences
import org.klaud.StorageHelper
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var deviceAdapter: ConnectedDeviceAdapter
    private lateinit var storageInfoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "Available Storage"
            textSize = 18f
            setPadding(0, 0, 0, 8)
        })

        storageInfoText = TextView(this).apply {
            text = "Loading..."
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        root.addView(storageInfoText)

        root.addView(TextView(this).apply {
            text = "Sync Frequency"
            textSize = 18f
            setPadding(0, 0, 0, 8)
        })

        val currentHours = SyncPreferences.getIntervalHours()
        val labels = SyncPreferences.OPTIONS.map { it.second }.toTypedArray()
        val values = SyncPreferences.OPTIONS.map { it.first }
        val currentIndex = values.indexOf(currentHours).coerceAtLeast(0)

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                labels
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(currentIndex)
        }
        root.addView(spinner)

        root.addView(TextView(this).apply {
            text = "File changes always trigger an immediate sync."
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 32)
        })

        val saveBtn = Button(this).apply {
            text = "Save Interval"
            setOnClickListener {
                val selectedHours = values[spinner.selectedItemPosition]
                SyncPreferences.setIntervalHours(selectedHours)
                ScheduledSyncWorker.reschedule(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(saveBtn)

        val syncNowBtn = Button(this).apply {
            text = "Sync Now"
            setOnClickListener {
                SyncManager.triggerFullSync(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Sync started...", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(syncNowBtn)

        root.addView(TextView(this).apply {
            text = "Connected Devices"
            textSize = 18f
            setPadding(0, 48, 0, 16)
        })

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }
        deviceAdapter = ConnectedDeviceAdapter()
        recyclerView.adapter = deviceAdapter
        root.addView(recyclerView)

        setContentView(root)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Settings"
        }

        DeviceManager.addListener {
            runOnUiThread {
                deviceAdapter.updateDevices(DeviceManager.getAllDevices())
            }
        }
        deviceAdapter.updateDevices(DeviceManager.getAllDevices())
    }

    override fun onResume() {
        super.onResume()
        updateStorageInfo()
    }

    private fun updateStorageInfo() {
        val available = StorageHelper.getAvailableBytes()
        val total = StorageHelper.getTotalBytes()
        val used = total - available
        storageInfoText.text = "${StorageHelper.formatBytes(available)} free  ·  " +
                        "${StorageHelper.formatBytes(used)} used  ·  " +
                        "${StorageHelper.formatBytes(total)} total"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private class ConnectedDeviceAdapter : RecyclerView.Adapter<ConnectedDeviceAdapter.ViewHolder>() {
        private var devices = listOf<org.klaud.Device>()
        private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

        fun updateDevices(newDevices: List<org.klaud.Device>) {
            devices = newDevices
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 16)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.nameText.text = device.name
            holder.addressText.text = device.onionAddress
            holder.statusText.text = if (device.isOnline) "ONLINE" else "offline"
            holder.statusText.setTextColor(if (device.isOnline) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
            holder.lastSeenText.text = "Last seen: ${dateFormat.format(Date(device.lastSeen))}"
        }

        override fun getItemCount() = devices.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = TextView(view.context).apply { textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD) }
            val addressText: TextView = TextView(view.context).apply { textSize = 12f; setTextColor(0xFF666666.toInt()) }
            val statusText: TextView = TextView(view.context).apply { textSize = 12f }
            val lastSeenText: TextView = TextView(view.context).apply { textSize = 11f; setTextColor(0xFF888888.toInt()) }

            init {
                (view as LinearLayout).apply {
                    addView(nameText)
                    addView(addressText)
                    addView(statusText)
                    addView(lastSeenText)
                }
            }
        }
    }
}