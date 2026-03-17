package org.klaud.crypto

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted session using AES-256-GCM for frame-based communication.
 * Frame format: [12B IV][4B ciphertext_len (big-endian)][ciphertext + 16B tag]
 */
class CryptoSession(private val socket: Socket, private val sessionKey: ByteArray) {
    companion object {
        private const val TAG = "CryptoSession"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128 // bits
    }

    private val inputStream = DataInputStream(socket.getInputStream())
    private val outputStream = DataOutputStream(socket.getOutputStream())
    private val random = SecureRandom()

    /**
     * Encrypt and send frame: [12B random IV][4B ciphertext_len][AES-256-GCM ciphertext+tag]
     */
    fun sendFrame(plaintext: ByteArray) {
        try {
            val iv = ByteArray(GCM_IV_LENGTH)
            random.nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(sessionKey, "AES")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)

            val ciphertext = cipher.doFinal(plaintext)

            outputStream.write(iv)
            outputStream.writeInt(ciphertext.size)
            outputStream.write(ciphertext)
            outputStream.flush()

            Log.d(TAG, "Sent encrypted frame: ${plaintext.size} bytes plaintext")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending encrypted frame", e)
            throw e
        }
    }

    fun receiveFrame(): ByteArray {
        try {
            val iv = ByteArray(GCM_IV_LENGTH)
            inputStream.readFully(iv)

            val ciphertextLength = inputStream.readInt()
            if (ciphertextLength < 0 || ciphertextLength > 10 * 1024 * 1024) {
                throw IllegalStateException("Invalid ciphertext length: $ciphertextLength")
            }
            if (ciphertextLength == 0) return ByteArray(0)

            val ciphertext = ByteArray(ciphertextLength)
            inputStream.readFully(ciphertext)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(sessionKey, "AES")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)

            val plaintext = cipher.doFinal(ciphertext)

            Log.d(TAG, "Received encrypted frame: ${plaintext.size} bytes plaintext")
            return plaintext
        } catch (e: EOFException) {
            return ByteArray(0)
        } catch (e: IOException) {
            if (!socket.isConnected || socket.isClosed) return ByteArray(0)
            Log.e(TAG, "Error receiving encrypted frame: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving encrypted frame: ${e.message}")
            throw e
        }
    }

    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }
    }
}
