package org.klaud

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.klaud.crypto.KyberKeyManager
import org.klaud.onion.TorManager
import org.klaud.utils.TorDeviceQRData

class PairingManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)

    data class OwnPairingData(val onionAddress: String, val publicKeyHash: String)

    fun getOwnPairingData(): OwnPairingData {
        val onion = TorManager.getOnionHostname() ?: "pending.onion"
        val hash = KyberKeyManager.getPublicKeyHash()
        return OwnPairingData(onion, hash)
    }

    fun pairWith(data: TorDeviceQRData) {
        scope.launch {
            NetworkManager.performPairingHandshake(
                context,
                data.onionAddress,
                data.port,
                data.pubKeyHash ?: "",
                data.deviceName
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: PairingManager? = null

        fun getInstance(context: Context): PairingManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PairingManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
