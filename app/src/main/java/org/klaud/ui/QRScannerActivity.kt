package org.klaud.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.*
import org.klaud.NetworkManager
import org.klaud.R
import org.klaud.SyncManager
import org.klaud.crypto.KyberKeyManager
import org.klaud.onion.TorManager
import org.klaud.utils.QRCodeUtils

sealed class PairingResult {
    data class Success(val deviceName: String) : PairingResult()
    object AlreadyPaired : PairingResult()
    object Timeout : PairingResult()
    object InvalidQr : PairingResult()
    data class Error(val message: String) : PairingResult()
}

class QRScannerActivity : AppCompatActivity() {

    private lateinit var barcodeView: CompoundBarcodeView
    private lateinit var qrImageView: ImageView
    private lateinit var statusLabel: TextView
    private lateinit var deviceNameLabel: TextView

    private lateinit var pairingOverlay: LinearLayout
    private lateinit var pairingProgress: ProgressBar
    private lateinit var pairingStatusText: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnCancel: Button
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            barcodeView.decodeContinuous(callback)
        } else {
            Toast.makeText(this, "Camera permission is required for scanning", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_qr_scanner)

        barcodeView = findViewById(R.id.zxing_barcode_scanner)
        qrImageView = findViewById(R.id.qr_image_view)
        statusLabel = findViewById(R.id.status_label)
        deviceNameLabel = findViewById(R.id.my_device_name)

        pairingOverlay = findViewById(R.id.pairing_overlay)
        pairingProgress = findViewById(R.id.pairing_progress)
        pairingStatusText = findViewById(R.id.pairing_status_text)
        btnRetry = findViewById(R.id.btn_retry)
        btnCancel = findViewById(R.id.btn_cancel)

        deviceNameLabel.text = android.os.Build.MODEL ?: "Android Device"

        btnCancel.setOnClickListener { finish() }
        btnRetry.setOnClickListener {
            pairingOverlay.visibility = View.GONE
            btnRetry.visibility = View.GONE
            barcodeView.resume()
        }

        setupScanner()
        startOwnQRGenerationLoop()
    }

    private fun setupScanner() {
        if (checkCameraPermission()) {
            barcodeView.decodeContinuous(callback)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult) {
            if (result.text != null) {
                barcodeView.pause()
                onQRCodeScanned(result.text)
            }
        }
    }

    private fun startOwnQRGenerationLoop() {
        scope.launch {
            while (isActive) {
                val onionAddress = TorManager.getOnionHostname()
                val onionPort = TorManager.getOnionPort()
                val pubKeyHash = KyberKeyManager.getPublicKeyHash()
                
                if (onionAddress != null && onionPort != null) {
                    val deviceName = android.os.Build.MODEL ?: "Android Device"
                    val qrBitmap = QRCodeUtils.generateTorDeviceQRCode(
                        onionAddress = onionAddress,
                        port = onionPort,
                        deviceName = deviceName,
                        pubKeyHash = pubKeyHash,
                        size = 512
                    )
                    
                    if (qrBitmap != null) {
                        qrImageView.setImageBitmap(qrBitmap)
                        statusLabel.text = "✓ Ready for pairing"
                        statusLabel.setTextColor(ContextCompat.getColor(this@QRScannerActivity, android.R.color.holo_green_dark))
                    }
                } else {
                    statusLabel.text = "Waiting for Tor... (initializing)"
                }
                delay(10000)
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun onQRCodeScanned(qrData: String) {
        val torDeviceData = QRCodeUtils.parseTorDeviceQRData(qrData)
        
        if (torDeviceData != null) {
                startPairingHandshake(
                    torDeviceData.onionAddress, 
                    torDeviceData.port, 
                    torDeviceData.deviceName,
                    torDeviceData.pubKeyHash ?: ""
                )
        } else {
            Toast.makeText(this, "Invalid Klaud QR code", Toast.LENGTH_SHORT).show()
            barcodeView.resume()
        }
    }

    private fun startPairingHandshake(onionAddress: String, port: Int, deviceName: String, publicKeyHash: String) {
        pairingOverlay.visibility = View.VISIBLE
        pairingProgress.visibility = View.VISIBLE
        pairingStatusText.text = "Connecting to $deviceName..."
        pairingStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        btnRetry.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(45_000L) {
                        NetworkManager.performPairingHandshake(
                            context = applicationContext,
                            onionAddress = onionAddress,
                            port = port,
                            expectedPubKeyHash = publicKeyHash,
                            deviceName = deviceName
                        )
                    } ?: PairingResult.Timeout
                } catch (e: Exception) {
                    PairingResult.Error(e.message ?: "Unknown error")
                }
            }

            pairingProgress.visibility = View.GONE
            when (result) {
                is PairingResult.Success -> {
                    pairingStatusText.text = "✓ ${result.deviceName} successfully paired!"
                    pairingStatusText.setTextColor(ContextCompat.getColor(this@QRScannerActivity, android.R.color.holo_green_light))
                    SyncManager.triggerFullSync(this@QRScannerActivity)
                    delay(1500)
                    finish()
                }
                is PairingResult.AlreadyPaired -> {
                    pairingStatusText.text = "Device already known."
                    delay(1500)
                    finish()
                }
                PairingResult.Timeout -> {
                    pairingStatusText.text = "Timeout – device not reachable."
                    pairingStatusText.setTextColor(ContextCompat.getColor(this@QRScannerActivity, android.R.color.holo_red_light))
                    btnRetry.visibility = View.VISIBLE
                }
                PairingResult.InvalidQr -> {
                    pairingStatusText.text = "Invalid QR code."
                    pairingStatusText.setTextColor(ContextCompat.getColor(this@QRScannerActivity, android.R.color.holo_red_light))
                    btnRetry.visibility = View.VISIBLE
                }
                is PairingResult.Error -> {
                    pairingStatusText.text = "Error: ${result.message}"
                    pairingStatusText.setTextColor(ContextCompat.getColor(this@QRScannerActivity, android.R.color.holo_red_light))
                    btnRetry.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (pairingOverlay.visibility != View.VISIBLE) {
            barcodeView.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
