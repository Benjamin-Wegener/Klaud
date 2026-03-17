package org.klaud.crypto

import android.util.Log
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.klaud.DeviceManager
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest

object HandshakeHandler {
    private const val TAG = "HandshakeHandler"
    private const val HKDF_SALT = "klaud-v1"
    private const val SESSION_KEY_LENGTH = 32

    fun performServerHandshake(socket: Socket): Pair<CryptoSession, String?> {
        val out = DataOutputStream(socket.getOutputStream())
        val inp = DataInputStream(socket.getInputStream())

        try {
            val pkA = KyberKeyManager.getPublicKeyBytes()
            writeFrame(out, pkA)

            val pkB = readFrame(inp)
            
            val digest = MessageDigest.getInstance("SHA-256")
            val hashB = digest.digest(pkB).joinToString("") { "%02x".format(it) }
            val peerOnion = DeviceManager.getAllDevices().find { it.publicKeyHash == hashB }?.onionAddress

            val ctAB = readFrame(inp)
            val ssAB = KyberKeyManager.decapsulate(ctAB)

            val (ctBA, ssBA) = KyberKeyManager.encapsulate(pkB)
            writeFrame(out, ctBA)

            val sessionKey = deriveSessionKey(ssAB, ssBA)
            return Pair(CryptoSession(socket, sessionKey), peerOnion)
        } catch (e: Exception) {
            try { socket.close() } catch (ex: IOException) {}
            throw e
        }
    }

    fun performClientHandshake(socket: Socket, expectedPeerPubKeyHash: String? = null): CryptoSession {
        val out = DataOutputStream(socket.getOutputStream())
        val inp = DataInputStream(socket.getInputStream())

        try {
            val pkA = readFrame(inp)
            if (expectedPeerPubKeyHash != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                val actualHash = digest.digest(pkA).joinToString("") { "%02x".format(it) }
                if (actualHash != expectedPeerPubKeyHash) throw SecurityException("MITM detected")
            }

            val pkB = KyberKeyManager.getPublicKeyBytes()
            writeFrame(out, pkB)

            val (ctAB, ssAB) = KyberKeyManager.encapsulate(pkA)
            writeFrame(out, ctAB)

            val ctBA = readFrame(inp)
            val ssBA = KyberKeyManager.decapsulate(ctBA)

            return CryptoSession(socket, deriveSessionKey(ssAB, ssBA))
        } catch (e: Exception) {
            try { socket.close() } catch (ex: IOException) {}
            throw e
        }
    }

    private fun deriveSessionKey(ssAB: ByteArray, ssBA: ByteArray): ByteArray {
        val input = ssAB + ssBA
        val hkdfGen = HKDFBytesGenerator(SHA256Digest())
        hkdfGen.init(HKDFParameters(input, HKDF_SALT.toByteArray(), "".toByteArray()))
        val sessionKey = ByteArray(SESSION_KEY_LENGTH)
        hkdfGen.generateBytes(sessionKey, 0, SESSION_KEY_LENGTH)
        return sessionKey
    }

    private fun writeFrame(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        out.write(data)
        out.flush()
    }

    private fun readFrame(inp: DataInputStream): ByteArray {
        val length = inp.readInt()
        val data = ByteArray(length)
        inp.readFully(data)
        return data
    }
}
