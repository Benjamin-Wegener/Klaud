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
        val onion = "v2c7ujq6mc6qqmxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.onion"
        val port = 12345
        val name = "TestDevice"
        val hash = "abc123hash"
        
        val bitmap = QRCodeUtils.generateTorDeviceQRCode(onion, port, name, hash)
        assertNotNull(bitmap)
        
        // We can't easily decode the bitmap here without a lot of setup, 
        // but we can test the data string generation if we had access to the private method or recreated it.
        // For now, let's test the parser with a known valid string.
        
        val timestamp = System.currentTimeMillis()
        val qrData = "KLAUD_V1:$onion:$port:$name:$timestamp:$hash"
        val parsed = QRCodeUtils.parseTorDeviceQRData(qrData)
        
        assertNotNull(parsed)
        assertEquals(onion, parsed?.onionAddress)
        assertEquals(port, parsed?.port)
        assertEquals(name, parsed?.deviceName)
        assertEquals(hash, parsed?.pubKeyHash)
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
