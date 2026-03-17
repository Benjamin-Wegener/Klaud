package org.klaud

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object FileRepository {
    private lateinit var syncRoot: File
    private val sha256Cache = ConcurrentHashMap<String, String>()
    private val syncStatus = ConcurrentHashMap<String, MutableSet<String>>()

    fun initialize(context: Context) {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        syncRoot = File(baseDir, "Klaud")
        if (!syncRoot.exists()) {
            syncRoot.mkdirs()
        }
    }

    fun getSyncRoot(): File = syncRoot

    data class SyncFile(val file: File, val relativePath: String, val isDirectory: Boolean)

    fun listFiles(dir: File = syncRoot): List<SyncFile> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { !it.name.endsWith(".part") }
            .map { file ->
                SyncFile(file, getRelativePath(file), file.isDirectory)
            }.sortedWith(compareBy({ !it.isDirectory }, { it.file.name.lowercase() }))
    }

    fun listFilesRecursive(dir: File = syncRoot): List<SyncFile> {
        val result = mutableListOf<SyncFile>()
        val files = dir.listFiles() ?: return emptyList()
        for (file in files) {
            if (file.name.endsWith(".part")) continue

            if (file.isDirectory) {
                result.addAll(listFilesRecursive(file))
            } else {
                result.add(SyncFile(file, getRelativePath(file), false))
            }
        }
        return result
    }

    fun getRelativePath(file: File): String {
        return file.absolutePath.removePrefix(syncRoot.absolutePath).trimStart(File.separatorChar)
    }

    fun getFileByRelativePath(relativePath: String): File {
        return File(syncRoot, relativePath)
    }

    fun computeSha256Cached(file: File): String? {
        if (!file.exists() || file.isDirectory) return null
        val cacheKey = "${getRelativePath(file)}:${file.lastModified()}"
        sha256Cache[cacheKey]?.let { return it }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536) // 64KB
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = input.read(buffer)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            sha256Cache[cacheKey] = hash
            hash
        } catch (e: Exception) {
            null
        }
    }

    fun createFile(relativePath: String): File {
        val file = File(syncRoot, relativePath)
        file.parentFile?.mkdirs()
        return file
    }

    fun deleteFile(relativePath: String): Boolean {
        val file = File(syncRoot, relativePath)
        return if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
    }

    fun renameFile(relativePath: String, newName: String): Boolean {
        val file = File(syncRoot, relativePath)
        val newFile = File(file.parentFile, newName)
        return file.renameTo(newFile)
    }

    fun createFolder(relativePath: String): Boolean {
        val folder = File(syncRoot, relativePath)
        return folder.mkdirs()
    }

    fun getMimeType(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    // New methods for sync tracking
    fun markFileSynced(relativePath: String, deviceId: String) {
        val key = getRelativePath(File(syncRoot, relativePath))
        val devices = syncStatus.getOrPut(key) { Collections.synchronizedSet(mutableSetOf()) }
        devices.add(deviceId)
    }

    fun getFileHealth(relativePath: String): Int {
        val key = getRelativePath(File(syncRoot, relativePath))
        val devices = syncStatus[key]
        val totalDevices = DeviceManager.getAllDevices().size
        if (totalDevices == 0) return 0
        return (devices?.size ?: 0) * 100 / totalDevices
    }

    fun clearSyncStatus() {
        syncStatus.clear()
    }
}
