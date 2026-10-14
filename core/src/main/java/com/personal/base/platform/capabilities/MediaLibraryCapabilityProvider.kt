package com.personal.base.platform.capabilities

import android.content.Context
import android.provider.MediaStore
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class MediaItem(
    val id: Long,
    val title: String,
    val mimeType: String,
    val sizeBytes: Long
)

/**
 * 8. MediaLibraryCapabilityProvider: photos, videos, and music collections via MediaStore.
 */
class MediaLibraryCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.MEDIA_LIBRARY

    fun getRecentImages(limit: Int = 10): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE
            )
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT $limit"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sort
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    list.add(
                        MediaItem(
                            id = cursor.getLong(idIdx),
                            title = cursor.getString(nameIdx) ?: "Image",
                            mimeType = cursor.getString(mimeIdx) ?: "image/jpeg",
                            sizeBytes = cursor.getLong(sizeIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.w("MediaLibraryCapability", "Error querying images: ${e.message}")
        }
        return list
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
