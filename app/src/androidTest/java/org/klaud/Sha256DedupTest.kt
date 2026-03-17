package org.klaud

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Sha256DedupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sameContentSameHash() {
        val file1 = tempFolder.newFile("f1.txt").apply { writeText("same content") }
        val file2 = tempFolder.newFile("f2.txt").apply { writeText("same content") }
        
        val hash1 = FileRepository.computeSha256Cached(file1)
        val hash2 = FileRepository.computeSha256Cached(file2)
        
        assertEquals(hash1, hash2)
    }

    @Test
    fun changeContentDifferentHash() {
        val file = tempFolder.newFile("f.txt").apply { writeText("original") }
        val hash1 = FileRepository.computeSha256Cached(file)
        
        // Ensure timestamp changes so cache is invalidated
        val newTime = file.lastModified() + 2000L
        file.writeText("modified")
        file.setLastModified(newTime)
        
        val hash2 = FileRepository.computeSha256Cached(file)
        
        assertNotEquals("Hashes should be different after content change", hash1, hash2)
    }
}
