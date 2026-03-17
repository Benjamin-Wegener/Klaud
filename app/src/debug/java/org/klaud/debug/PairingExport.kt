package org.klaud.debug

import android.content.Context
import android.util.Log
import org.klaud.PairingManager
import org.klaud.crypto.KyberKeyManager
import org.klaud.onion.TorManager
import java.io.File

object PairingExport {
    private const val TAG = "PairingExport"

    fun exportPairingData(context: Context) {
        val onion = TorManager.getOnionHostname()
        if (onion == null || onion == "pending.onion" || !onion.endsWith(".onion")) {
            Log.d(TAG, "Skipping export: Tor not ready yet ($onion)")
            return
        }

        try {
            val hash = KyberKeyManager.getPublicKeyHash()
            val port = TorManager.getOnionPort() ?: 10001
            val json = """{"onion":"$onion","port":$port,"key":"$hash"}"""
            File(context.getExternalFilesDir(null), "pairing.json").writeText(json)
            Log.i(TAG, "pairing.json exported: $onion")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export pairing.json", e)
        }
    }
}
