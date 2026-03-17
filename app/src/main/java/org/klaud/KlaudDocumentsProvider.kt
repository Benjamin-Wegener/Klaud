package org.klaud

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import kotlinx.coroutines.*
import org.klaud.onion.TorManager

class KlaudDocumentsProvider : DocumentsProvider() {

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val AUTHORITY = "org.klaud.documents"
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )
    }

    override fun onCreate(): Boolean {
        FileRepository.initialize(context!!)
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "klaud_root")
            add(Root.COLUMN_TITLE, "Klaud")
            add(Root.COLUMN_SUMMARY, "Encrypted P2P Sync")
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or
                Root.FLAG_SUPPORTS_RECENTS or
                Root.FLAG_SUPPORTS_SEARCH)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_DOCUMENT_ID, "root")
            add(Root.COLUMN_AVAILABLE_BYTES, StorageHelper.getAvailableBytes())
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addFileRow(result, getFileForDocumentId(documentId), documentId)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        getFileForDocumentId(parentDocumentId)
            .listFiles()
            ?.filter { !it.name.endsWith(".part") }
            ?.forEach { addFileRow(result, it, getDocumentIdForFile(it)) }
        return result
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        return ParcelFileDescriptor.open(
            getFileForDocumentId(documentId),
            ParcelFileDescriptor.parseMode(mode)
        )
    }

    override fun createDocument(
        parentDocumentId: String, mimeType: String, displayName: String
    ): String {
        val parent = getFileForDocumentId(parentDocumentId)
        val file = File(parent, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) file.mkdirs() else file.createNewFile()
        return getDocumentIdForFile(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = getFileForDocumentId(documentId)
        val relativePath = FileRepository.getRelativePath(file)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
        val ctx = context ?: return
        repositoryScope.launch {
            val socksPort = TorManager.getSocksPort() ?: return@launch
            DeviceManager.getSyncTargets().forEach { device ->
                FileSyncService.sendDeletionToOnion(
                    device.onionAddress, device.port, relativePath, socksPort, ctx
                )
            }
            DeviceManager.getAllDevices().filter { !it.isOnline }.forEach { device ->
                PendingRelayQueue.addDeletion(device.id, relativePath)
            }
        }
    }

    private fun getFileForDocumentId(documentId: String): File =
        if (documentId == "root") FileRepository.getSyncRoot()
        else File(FileRepository.getSyncRoot(), documentId.removePrefix("root/"))

    private fun getDocumentIdForFile(file: File): String {
        val relative = FileRepository.getRelativePath(file)
        return if (relative.isEmpty()) "root" else "root/$relative"
    }

    private fun addFileRow(cursor: MatrixCursor, file: File, documentId: String) {
        val mimeType = if (file.isDirectory) Document.MIME_TYPE_DIR
                       else FileRepository.getMimeType(file)
        var flags = 0
        if (file.isDirectory) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (file.canWrite()) flags = flags or
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        if (mimeType.startsWith("image/")) flags = flags or
            Document.FLAG_SUPPORTS_THUMBNAIL
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
        }
    }
}
