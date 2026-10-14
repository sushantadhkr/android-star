package com.personal.base.core.runtime

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.personal.base.core.logging.PlatformLogger
import java.io.File

/**
 * Helper for generating secure content:// URIs for external app sharing.
 */
class FileProviderConfig(
    private val context: Context,
    private val logger: PlatformLogger
) {
    val authority: String = "${context.packageName}.fileprovider"

    fun getUriForFile(file: File): Uri {
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun getInternalFile(relativePath: String): File {
        return File(context.filesDir, relativePath)
    }
}
