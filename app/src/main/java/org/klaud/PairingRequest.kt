package org.klaud

data class PairingRequest(
    val onionAddress: String,
    val port: Int,
    val deviceName: String,
    val publicKeyHash: String,
    val token: String
)
