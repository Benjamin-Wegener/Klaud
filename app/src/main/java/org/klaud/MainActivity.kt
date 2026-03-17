package org.klaud

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.klaud.databinding.ActivityMainBinding
import org.klaud.onion.TorManager
import org.klaud.ui.FileListFragment
import org.klaud.ui.QRScannerActivity
import org.klaud.ui.SettingsActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, FileListFragment())
                .commit()
        }

        setupUI()
        monitorTorStatus()
        handleIntent(intent)

        if (BuildConfig.DEBUG) {
            try {
                val clazz = Class.forName("org.klaud.debug.PairingExportKt")
                val method = clazz.getDeclaredMethod("exportPairingData", android.content.Context::class.java)
                method.invoke(null, this)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
                    if (isRunning) {
                        binding.torStatusDot.backgroundTintList =
                            ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.klaud_tor_connected))
                        binding.torStatusText.text = "Tor Connected · ${onion!!.take(10)}…"
                    } else {
                        binding.torStatusDot.backgroundTintList =
                            ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.klaud_tor_connecting))
                        binding.torStatusText.text = "Connecting to Tor…"
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
}
