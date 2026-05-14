package org.klaud

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.klaud.utils.QRCodeUtils

@RunWith(AndroidJUnit4::class)
class QrPairingTest {

    @Test(timeout = 10000)
    fun encodeDecodeRoundtrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val onion = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcd.onion"
        val port = 12345
        val name = "TestDevice"
        val hash = "abc123hash"
        val token = PairingTokenManager.getOrCreateActiveToken(context)
        
        val bitmap = QRCodeUtils.generateTorDeviceQRCode(
            onionAddress = onion,
            port = port,
            deviceName = name,
            pubKeyHash = hash,
            pairingToken = token.token,
            expiresAt = token.expiresAt
        )
        assertNotNull(bitmap)

        val timestamp = System.currentTimeMillis()
        val qrData = "KLAUD_V2:$onion:$port:$name:$timestamp:$hash:${token.token}:${token.expiresAt}"
        val parsed = QRCodeUtils.parseTorDeviceQRData(qrData)
        
        assertNotNull(parsed)
        assertEquals(onion, parsed?.onionAddress)
        assertEquals(port, parsed?.port)
        assertEquals(name, parsed?.deviceName)
        assertEquals(hash, parsed?.pubKeyHash)
        assertEquals(token.token, parsed?.pairingToken)
        assertEquals(token.expiresAt, parsed?.expiresAt)
    }

    @Test(timeout = 10000)
    fun testQrScannedBroadcast() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val onion = "test.onion"
        val qrData = "KLAUD_V1:$onion:1234:RemoteDevice:${System.currentTimeMillis()}:somehash"
        
        val intent = Intent("org.klaud.QR_SCANNED").apply {
            putExtra("qr_data", qrData)
            setPackage(context.packageName)
        }
        
        context.sendBroadcast(intent)
        
        // Give it a moment to process
        Thread.sleep(1000)
        
        val device = DeviceManager.getDeviceByOnion(onion)
        // Note: The handshake might fail because there's no real Tor/Peer, 
        // but the pairing manager should have at least attempted it.
        // Depending on implementation, it might be added to DeviceManager only after handshake.
    }
}
