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

class PlatformDocumentsProvider : DocumentsProvider() {
    private val DEFAULT_ROOT_PROJECTION = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID
    )

    private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "android_star_root")
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Android Star Platform")
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY)
        return result
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId ?: "root")
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Platform Storage")
        row.add(DocumentsContract.Document.COLUMN_SIZE, 0)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = File(context?.filesDir, documentId ?: "file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

/**
 * 37. DocumentsProviderCapabilityProvider: SAF documents provider bridge for external apps.
 */
class DocumentsProviderCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DOCUMENTS_PROVIDER

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
