package org.klaud.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Hashtable

object QRCodeUtils {

    private const val QR_DATA_PREFIX = "KLAUD_V1"
    private const val QR_DATA_SEPARATOR = ":"

    fun generateTorDeviceQRCode(
        onionAddress: String,
        port: Int,
        deviceName: String,
        pubKeyHash: String,
        size: Int = 500
    ): Bitmap? {
        if (!isValidOnionAddress(onionAddress)) {
            throw IllegalArgumentException("Invalid onion address format: $onionAddress")
        }

        if (port !in 1..65535) {
            throw IllegalArgumentException("Invalid port number: $port")
        }

        val cleanDeviceName = deviceName.take(50).trim().replace(QR_DATA_SEPARATOR, "_")
        if (cleanDeviceName.isEmpty()) {
            throw IllegalArgumentException("Device name cannot be empty")
        }

        val timestamp = System.currentTimeMillis()
        val qrData = "$QR_DATA_PREFIX$QR_DATA_SEPARATOR$onionAddress$QR_DATA_SEPARATOR$port$QR_DATA_SEPARATOR$cleanDeviceName$QR_DATA_SEPARATOR$timestamp$QR_DATA_SEPARATOR$pubKeyHash"

        return generateQRCode(qrData, size)
    }

    fun parseTorDeviceQRData(qrData: String): TorDeviceQRData? {
        try {
            val parts = qrData.split(QR_DATA_SEPARATOR, limit = 6)
            
            if (parts.size < 5 || parts[0] != QR_DATA_PREFIX) {
                return null
            }

            val onionAddress = parts[1]
            val port = parts[2].toIntOrNull()
            val deviceName = parts[3]
            val timestamp = parts[4].toLongOrNull()
            val pubKeyHash = if (parts.size >= 6) parts[5] else null

            if (!isValidOnionAddress(onionAddress) || 
                port == null || port !in 1..65535 ||
                deviceName.isEmpty() || timestamp == null) {
                return null
            }

            return TorDeviceQRData(
                onionAddress = onionAddress,
                port = port,
                deviceName = deviceName,
                timestamp = timestamp,
                pubKeyHash = pubKeyHash,
                qrData = qrData
            )

        } catch (e: Exception) {
            return null
        }
    }

    fun isValidOnionAddress(address: String): Boolean {
        return address.endsWith(".onion") && 
               (address.length == 22 || address.length == 62)
    }

    fun generateQRCode(data: String, size: Int = 500): Bitmap? {
        return try {
            val hints = Hashtable<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H

            val writer = MultiFormatWriter()
            val bitMatrix: BitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }

            bmp
        } catch (e: Exception) {
            null
        }
    }
}

data class TorDeviceQRData(
    val onionAddress: String,
    val port: Int,
    val deviceName: String,
    val timestamp: Long,
    val pubKeyHash: String? = null,
    val qrData: String
)
