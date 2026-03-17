package org.klaud.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.klaud.PairingManager
import org.klaud.utils.QRCodeUtils

class QrInjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "org.klaud.QR_SCANNED") return
        val qrData = intent.getStringExtra("qr_data") ?: return
        val pairData = QRCodeUtils.parseTorDeviceQRData(qrData) ?: return
        PairingManager.getInstance(context).pairWith(pairData)
    }
}
