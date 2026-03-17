package org.klaud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineQueueTest {

    private lateinit var queue: OfflineQueue

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        queue = OfflineQueue(context)
        queue.clear()
    }

    @Test(timeout = 10000)
    fun queuePersistenceTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        queue.enqueue("file1.txt", "onion1")
        assertEquals(1, queue.getItems().size)
        
        // Re-instantiate to test persistence
        val newQueue = OfflineQueue(context)
        assertEquals(1, newQueue.getItems().size)
        assertEquals("file1.txt", newQueue.getItems()[0].relativePath)
    }

    @Test(timeout = 10000)
    fun noDuplicatesTest() {
        queue.enqueue("file1.txt", "onion1")
        queue.enqueue("file1.txt", "onion1")
        assertEquals(1, queue.getItems().size)
    }
}
