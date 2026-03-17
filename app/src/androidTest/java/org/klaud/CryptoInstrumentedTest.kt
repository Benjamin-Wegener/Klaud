package org.klaud

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.klaud.crypto.CryptoSession
import org.klaud.crypto.KyberKeyManager
import java.net.ServerSocket
import java.net.Socket
import java.security.Security
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class CryptoInstrumentedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    @Test(timeout = 10000)
    fun aesGcmRoundtrip() {
        val server = ServerSocket(0)
        val sessionKey = ByteArray(32) { it.toByte() }
        val plaintext = "Hello Klaud AES GCM".toByteArray()
        
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val client = server.accept()
            val session = CryptoSession(client, sessionKey)
            session.sendFrame(plaintext)
        }

        val socket = Socket("127.0.0.1", server.localPort)
        val session = CryptoSession(socket, sessionKey)
        val received = session.receiveFrame()
        
        assertArrayEquals(plaintext, received)
        server.close()
    }

    @Test(timeout = 10000)
    fun kyberHandshakeRoundtrip() {
        val alicePubKey = KyberKeyManager.getPublicKeyBytes()
        
        // Bob encapsulates for Alice
        val (ciphertext, bobSecret) = KyberKeyManager.encapsulate(alicePubKey)
        
        // Alice decapsulates
        val aliceSecret = KyberKeyManager.decapsulate(ciphertext)
        
        assertArrayEquals(bobSecret, aliceSecret)
    }

    @Test(timeout = 10000)
    fun differentIvPerFrame() {
        val server = ServerSocket(0)
        val sessionKey = ByteArray(32) { 0x01 }
        val data = "test".toByteArray()
        
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            val client = server.accept()
            val session = CryptoSession(client, sessionKey)
            session.sendFrame(data)
            session.sendFrame(data)
        }

        val socket = Socket("127.0.0.1", server.localPort)
        val input = socket.getInputStream()
        
        val frame1 = ByteArray(12 + 4 + 4 + 16) // IV(12) + Len(4) + Cipher(4) + Tag(16)
        input.read(frame1)
        val frame2 = ByteArray(12 + 4 + 4 + 16)
        input.read(frame2)
        
        val iv1 = frame1.sliceArray(0 until 12)
        val iv2 = frame2.sliceArray(0 until 12)
        
        assertFalse(iv1.contentEquals(iv2))
        server.close()
    }
}
