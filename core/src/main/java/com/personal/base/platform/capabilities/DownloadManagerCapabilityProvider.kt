package com.personal.base.platform.capabilities

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 24. DownloadManagerCapabilityProvider: queue, track, and manage background downloads.
 */
class DownloadManagerCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DOWNLOAD_MANAGER

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    fun enqueueDownload(url: String, fileName: String, description: String = "Platform Download"): Long {
        if (downloadManager == null) return -1L
        return try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription(description)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val id = downloadManager.enqueue(request)
            logger.i("DownloadManager", "Enqueued download ID: $id for $fileName")
            id
        } catch (e: Exception) {
            logger.e("DownloadManager", "Failed to enqueue download: ${e.message}", e.message)
            -1L
        }
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
