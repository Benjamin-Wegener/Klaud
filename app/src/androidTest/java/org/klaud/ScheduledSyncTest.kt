package org.klaud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledSyncTest {

    @Test
    fun testScheduleSync() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Mock preferences if needed to ensure interval > 0
        SyncPreferences.initialize(context)
        // We might need to force an interval for the test
        
        ScheduledSyncWorker.reschedule(context)
        
        val workManager = WorkManager.getInstance(context)
        val workInfos = workManager.getWorkInfosForUniqueWork(ScheduledSyncWorker.WORK_NAME).get()
        
        assertFalse(workInfos.isEmpty())
        val state = workInfos[0].state
        assertTrue(state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING || state == WorkInfo.State.BLOCKED)
    }
}
