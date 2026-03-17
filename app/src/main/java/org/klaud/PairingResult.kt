package org.klaud

sealed class PairingResult {
    data class Success(val deviceName: String) : PairingResult()
    object AlreadyPaired : PairingResult()
    object Timeout : PairingResult()
    object InvalidQr : PairingResult()
    data class Error(val message: String) : PairingResult()
}
