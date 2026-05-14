package org.klaud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileRepositorySecurityTest {

    @Test(expected = SecurityException::class)
    fun rejectsTraversalWhenResolvingRelativePath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FileRepository.initialize(context)

        FileRepository.getFileByRelativePath("../escape.txt")
    }

    @Test
    fun deleteReturnsFalseForTraversalPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FileRepository.initialize(context)

        assertFalse(FileRepository.deleteFile("../escape.txt"))
    }
}
