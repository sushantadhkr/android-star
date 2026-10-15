package com.personal.base.platform.capabilities

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import java.io.File

/**
 * Android Star's download folder, exposed through the system file picker.
 *
 * Every other app's file chooser then shows "Android Star" alongside Downloads and Drive, which is
 * what makes files the platform fetched reachable from anywhere rather than only from here.
 *
 * Document ids are the file name, relative to [ROOT_ID]. Names are flattened and any separator is
 * rejected, so a document id can never walk out of the root — a picker is an untrusted entry point
 * like any other.
 */
class PlatformDocumentsProvider : DocumentsProvider() {

    private val rootDir: File?
        get() = context?.let { ctx ->
            // The same folder the download capability writes into, so what the picker offers is
            // exactly what was downloaded — not a second, divergent copy.
            File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), "Android Star")
        }

    /**
     * Local only, and deliberately no create flag.
     *
     * `createDocument` below refuses, so advertising `FLAG_SUPPORTS_CREATE` would offer the user a
     * "new folder" button that quietly does nothing — worse than not offering it. The two have to
     * agree, and a device test holds them to it.
     */
    private val rootFlags: Int
        get() = DocumentsContract.Root.FLAG_LOCAL_ONLY

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
        ))
        val row = cursor.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Android Star Downloads")
        row.add(DocumentsContract.Root.COLUMN_FLAGS, rootFlags)
        return cursor
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val id = documentId ?: ROOT_ID
        val file = resolve(id)

        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
        row.add(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            if (id == ROOT_ID) "Android Star Downloads" else (file?.name ?: id)
        )
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeOf(id, file))
        row.add(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            file?.lastModified() ?: 0L
        )
        row.add(
            DocumentsContract.Document.COLUMN_SIZE,
            if (id == ROOT_ID) 0L else (file?.length() ?: 0L)
        )
        // Read and delete only. Offering create here would promise a writable picker the provider
        // cannot honour, and a picker that silently drops writes is worse than one that says no.
        row.add(
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        )
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        // Only the root has children; anything deeper is a file, and a file has no children.
        if (parentDocumentId != ROOT_ID) return cursor

        val dir = rootDir ?: return cursor
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: return cursor

        for (file in files) {
            val row = cursor.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, file.name)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeOf(file.name, file))
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
            row.add(
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            )
        }
        return cursor
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        // Opened read-only whatever the caller asked for: the folder is the platform's output,
        // and other apps have no business writing into it through the picker.
        val file = resolve(documentId)
            ?: throw java.io.FileNotFoundException("No such document: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Deletes a document.
     *
     * Returns nothing and reports failure by throwing, which is what the platform expects — a
     * caller that asked to delete something that is not there gets FileNotFoundException, not a
     * silent success.
     */
    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
            ?: throw java.io.FileNotFoundException("No such document: $documentId")
        val root = rootDir
            ?: throw java.io.FileNotFoundException("No download folder on this device")
        // Belt and braces: refuse anything not strictly inside the root even if resolve() let it
        // through, because a delete is the one operation here that destroys data.
        if (file.canonicalFile.parentFile != root.canonicalFile) {
            throw java.io.FileNotFoundException("Refusing to delete outside the root: $documentId")
        }
        if (!file.delete()) throw java.io.FileNotFoundException("Could not delete $documentId")
    }

    /** Whether a document sits directly under a parent, as the picker needs to know to walk trees. */
    override fun isChildDocument(parentDocumentId: String?, documentId: String): Boolean {
        if (parentDocumentId != ROOT_ID) return false
        return resolve(documentId)?.isFile == true
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String {
        // Not implemented, and deliberately so — see the flags above. Saying so is better than a
        // document id that resolves to nothing.
        throw UnsupportedOperationException("Android Star's picker is read and delete only")
    }

    /**
     * Turns a document id into a real file, or null when it would escape the root.
     *
     * The null return is the important part: a hostile or buggy caller can send any string, and a
     * separator or ".." in it must resolve to nothing rather than to a path outside the folder.
     */
    private fun resolve(documentId: String?): File? {
        val root = rootDir ?: return null
        if (documentId.isNullOrBlank() || documentId == ROOT_ID) return null
        if (documentId.contains('/') || documentId.contains('\\') || documentId.contains("..")) return null
        val file = File(root, documentId)
        return if (file.canonicalFile.parentFile == root.canonicalFile) file else null
    }

    private fun mimeOf(id: String, file: File?): String {
        if (id == ROOT_ID) return DocumentsContract.Document.MIME_TYPE_DIR
        val map = android.webkit.MimeTypeMap.getSingleton()
        val extension = id.substringAfterLast('.', "")
        // Resolved by extension, because the only thing known about a document here is its name.
        // An unknown extension is named octet-stream rather than guessed at, so a caller that
        // trusts the type is told "unknown" instead of being handed a wrong one.
        return map.getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    companion object {
        const val ROOT_ID = "android_star_root"
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}

/**
 * The platform's own view of what the picker is offering.
 *
 * Separate from the [PlatformDocumentsProvider] above because the picker is called by other apps
 * through the Android framework, while this is called by our own API. Both read the same folder,
 * so a file listed here and a file shown in the picker are the same file.
 */
class DocumentsProviderCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DOCUMENTS_PROVIDER

    /** Where the picker points, so an API caller can find the same files. */
    val authority: String = "${context.packageName}.documents"

    private val folder: File
        get() = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "Android Star"
        )

    fun status(): DocumentsStatus {
        val exists = folder.isDirectory
        val files = if (exists) folder.listFiles()?.filter { it.isFile }.orEmpty() else emptyList()
        return DocumentsStatus(
            authority = authority,
            folderPath = folder.absolutePath,
            folderExists = exists,
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            androidStarRootId = PlatformDocumentsProvider.ROOT_ID,
            writable = false
        )
    }

    /** The same files the picker lists, with the same document ids. */
    fun list(limit: Int): List<DocumentEntry> {
        val dir = folder
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.map {
                DocumentEntry(
                    documentId = it.name,
                    name = it.name,
                    sizeBytes = it.length(),
                    lastModified = it.lastModified()
                )
            }
            .orEmpty()
    }

    /** Deletes a file by the same document id the picker uses. */
    fun delete(documentId: String): Boolean {
        val dir = folder
        if (!dir.isDirectory) return false
        if (documentId.isBlank() || documentId.contains('/') || documentId.contains("..")) return false
        val file = File(dir, documentId)
        // Same containment rule as the provider, so neither route can reach outside the folder.
        if (file.canonicalFile.parentFile != dir.canonicalFile) return false
        val deleted = file.delete()
        logger.i("DocumentsProvider", "Delete '$documentId' -> $deleted")
        return deleted
    }

    override fun checkStatus(): CapabilityStatus =
        if (folder.isDirectory) CapabilityStatus.Available
        else CapabilityStatus.Partial(
            whatWorks = "The provider is registered and will list itself in any file picker",
            whatDoesNot = "The folder does not exist yet; it is created on the first download"
        )

    override fun release() {}
}

/** Where the documents provider points and what it currently holds. */
data class DocumentsStatus(
    val authority: String,
    val folderPath: String,
    val folderExists: Boolean,
    val fileCount: Int,
    val totalBytes: Long,
    val androidStarRootId: String,
    val writable: Boolean
)

/** One file as both the API and the picker see it. */
data class DocumentEntry(
    val documentId: String,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)