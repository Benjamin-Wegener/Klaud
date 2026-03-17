package org.klaud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.klaud.crypto.CryptoSession
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FileSyncInstrumentedTest {

    @get:Rule
    val tempFolder = TemporaryFolder()
    
    private lateinit var testDir: File

    @Before
    fun setup() {
        testDir = tempFolder.newFolder("sync_test")
    }

    @Test(timeout = 10000)
    fun fileSyncRoundtripPreservesContent() {
        val server = ServerSocket(0)
        val port = server.localPort
        val sessionKey = ByteArray(32) { 0x42.toByte() }
        val content = "test data ${System.nanoTime()}".toByteArray()
        val fileName = "test_file.txt"
        
        val executor = Executors.newSingleThreadExecutor()
        
        // Simulating the server side receiving a file
        executor.submit {
            val client = server.accept()
            val session = CryptoSession(client, sessionKey)
            
            // Receive metadata frame
            val metaFrame = session.receiveFrame()
            // In a real scenario we'd parse this, but for the test we just consume it
            
            // Receive file content frames
            val receivedFile = File(testDir, fileName)
            receivedFile.outputStream().use { out ->
                while(true) {
                    val chunk = session.receiveFrame()
                    if (chunk.isEmpty()) break
                    out.write(chunk)
                }
            }
            session.sendFrame("OK".toByteArray())
        }

        // Simulating the client side sending a file
        val socket = Socket("127.0.0.1", port)
        val session = CryptoSession(socket, sessionKey)
        
        // Send a dummy metadata frame (type, path, size, hash, time)
        session.sendFrame(byteArrayOf(0x03) + fileName.toByteArray())
        
        // Send file content
        session.sendFrame(content)
        session.sendFrame(ByteArray(0)) // EOF
        
        val response = session.receiveFrame()
        assertEquals("OK", String(response))
        
        val savedFile = File(testDir, fileName)
        assertTrue(savedFile.exists())
        assertArrayEquals(content, savedFile.readBytes())
        
        server.close()
    }
}
