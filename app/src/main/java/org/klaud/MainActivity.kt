package org.klaud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.klaud.databinding.ActivityMainBinding
import org.klaud.onion.TorHiddenService
import org.klaud.onion.TorManager
import org.klaud.ui.FileListFragment
import org.klaud.ui.QRScannerActivity
import org.klaud.ui.SettingsActivity
import org.klaud.utils.QRCodeUtils
import org.klaud.utils.TorDeviceQRData
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val gson = Gson()

    private val qrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "org.klaud.QR_SCANNED") {
                val qrData = intent.getStringExtra("qr_data")
                    ?: intent.getStringExtra("qrfile")?.let { path ->
                        try { File(path).readText() } catch (e: Exception) { null }
                    }
                
                qrData?.let { raw ->
                    Log.d("MainActivity", "Received QR broadcast: $raw")
                    
                    var pairData = QRCodeUtils.parseTorDeviceQRData(raw)
                    
                    if (pairData == null && raw.trim().startsWith("{")) {
                        try {
                            val map = gson.fromJson(raw, Map::class.java)
                            pairData = TorDeviceQRData(
                                onionAddress = map["onion"] as String,
                                port = (map["port"] as Double).toInt(),
                                deviceName = "Remote Device",
                                timestamp = System.currentTimeMillis(),
                                pubKeyHash = map["key"] as String,
                                qrData = raw
                            )
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to parse JSON QR data", e)
                        }
                    }

                    pairData?.let { data ->
                        Log.i("MainActivity", "Pairing triggered for: ${data.onionAddress}")
                        PairingManager.getInstance(context).pairWith(data)
                    } ?: Log.w("MainActivity", "Could not parse broadcast data")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure Tor service is started when UI is visible (Android 14 safe)
        val torIntent = Intent(this, TorHiddenService::class.java)
        startForegroundService(torIntent)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        // Explicitly hide the title text
        supportActionBar?.setDisplayShowTitleEnabled(false)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FileListFragment())
                .commit()
        }

        setupUI()
        monitorTorStatus()
        handleIntent(intent)

        @Suppress("DEPRECATION")
        val filter = IntentFilter("org.klaud.QR_SCANNED")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(qrReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(qrReceiver, filter)
        }
    }

    private fun setupUI() {
        binding.fabAdd.isEnabled = true
        binding.fabAdd.alpha = 1.0f

        binding.fabAdd.setOnClickListener {
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container) as? FileListFragment
            fragment?.let {
                org.klaud.ui.AddFileBottomSheet.show(it.childFragmentManager)
            }
        }
    }

    private fun monitorTorStatus() {
        lifecycleScope.launch {
            while (isActive) {
                val onion = TorManager.getOnionHostname()
                val isRunning = TorManager.isTorRunning() && onion != null
                
                withContext(Dispatchers.Main) {
                    if (isRunning && BuildConfig.DEBUG) {
                        try {
                            val clazz = Class.forName("org.klaud.debug.PairingExport")
                            val method = clazz.getDeclaredMethod("exportPairingData", Context::class.java)
                            method.invoke(null, this@MainActivity)
                        } catch (e: Exception) {}
                    }

                    binding.fabAdd.isEnabled = true
                    binding.fabAdd.alpha = 1.0f
                }
                delay(3000)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_qr_scan -> {
                startActivity(Intent(this, QRScannerActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type != null) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { copyUriToRoot(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type != null) {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { copyUriToRoot(it) }
                }
            }
        }
    }

    private fun copyUriToRoot(uri: Uri) {
        val fileName = getFileName(uri) ?: "sharedfile_${System.currentTimeMillis()}"
        val fragment = supportFragmentManager
            .findFragmentById(R.id.fragment_container) as? FileListFragment
        val targetDir = fragment?.currentDir ?: FileRepository.getSyncRoot()
        val destFile = File(targetDir, fileName)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            SyncManager.triggerFileSync(this, FileRepository.getRelativePath(destFile), destFile)
            fragment?.refreshList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(qrReceiver)
        } catch (e: Exception) {}
    }
}
