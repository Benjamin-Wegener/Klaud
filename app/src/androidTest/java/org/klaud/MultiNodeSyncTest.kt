package org.klaud

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.klaud.onion.TorManager
import kotlinx.coroutines.runBlocking
import org.klaud.FileSyncService.Companion.PeerInfo

@RunWith(AndroidJUnit4::class)
class MultiNodeSyncTest {

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DeviceManager.initialize(context)
        // Clear devices for a clean test state
        val prefs = context.getSharedPreferences("klauddevices", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun teardown() {
        // Fake-Geräte nach jedem Test löschen
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("klauddevices", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test(timeout = 15000)
    fun test3NodeMeshDiscovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val myOnion = "nodeA.onion"
        
        // Scenario: Node A is paired with Node B.
        // Node B is paired with Node C.
        // Node A does NOT know Node C initially.
        
        runBlocking {
            DeviceManager.addDevice("nodeB.onion", 10001, "NodeB", "hashB")
        }
        
        assertEquals(1, DeviceManager.getAllDevices().size)
        assertNull(DeviceManager.getDeviceByOnion("nodeC.onion"))

        // Simulate Node B sending its peer list to Node A during a sync
        // Node B's peer list contains Node A and Node C
        val nodeBPeers = listOf(
            PeerInfo("nodeA.onion", 10001, "NodeA", "hashA"),
            PeerInfo("nodeC.onion", 10001, "NodeC", "hashC")
        )

        // This simulates the logic in FileSyncService.sendFileToOnion (receiving response)
        // or FileSyncService.handleClient (receiving request)
        nodeBPeers.forEach { peer ->
            if (peer.onionAddress != myOnion) {
                runBlocking {
                    DeviceManager.addDevice(peer.onionAddress, peer.port, peer.name, peer.publicKeyHash)
                }
            }
        }

        // Now Node A should know Node C
        val allDevices = DeviceManager.getAllDevices()
        assertEquals(2, allDevices.size)
        assertNotNull(DeviceManager.getDeviceByOnion("nodeC.onion"))
        assertEquals("NodeC", DeviceManager.getDeviceByOnion("nodeC.onion")?.name)
        
        // And it still knows Node B
        assertNotNull(DeviceManager.getDeviceByOnion("nodeB.onion"))
    }
}
