package org.klaud.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import org.klaud.PairingManager
import org.klaud.utils.QRCodeUtils
import org.klaud.utils.TorDeviceQRData
import java.io.File

class QrInjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "org.klaud.QR_SCANNED") return

        val raw = intent.getStringExtra("qr_data")
            ?: intent.getStringExtra("qrfile")?.let { path ->
                try { File(path).readText().trim() } catch (e: Exception) { null }
            } ?: return

        // Erst KLAUD_V1 Format versuchen
        var pairData: TorDeviceQRData? = QRCodeUtils.parseTorDeviceQRData(raw)

        // JSON-Fallback (von pairing.json / run_sync_test2.sh)
        if (pairData == null && raw.trim().startsWith("{")) {
            try {
                val map = Gson().fromJson(raw, Map::class.java)
                pairData = TorDeviceQRData(
                    onionAddress = map["onion"] as String,
                    port = (map["port"] as Double).toInt(),
                    deviceName = "Emulator",
                    timestamp = System.currentTimeMillis(),
                    pubKeyHash = map["key"] as? String,
                    qrData = raw
                )
            } catch (e: Exception) { return }
        }

        pairData?.let { PairingManager.getInstance(context).pairWith(it) }
    }
}
