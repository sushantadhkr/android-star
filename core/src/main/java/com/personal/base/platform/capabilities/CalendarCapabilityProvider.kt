package com.personal.base.platform.capabilities

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import java.util.TimeZone

data class CalendarEventItem(
    val id: Long,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val description: String?
)

/**
 * 3. CalendarCapabilityProvider: events read/write via CalendarContract.
 */
class CalendarCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CALENDAR

    fun getUpcomingEvents(limit: Int = 20): List<CalendarEventItem> {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val events = mutableListOf<CalendarEventItem>()
        try {
            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.DESCRIPTION
            )
            val selection = "${CalendarContract.Events.DTSTART} >= ?"
            val selectionArgs = arrayOf(System.currentTimeMillis().toString())
            val sortOrder = "${CalendarContract.Events.DTSTART} ASC LIMIT $limit"

            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndex(CalendarContract.Events.DTEND)
                val descIdx = cursor.getColumnIndex(CalendarContract.Events.DESCRIPTION)

                while (cursor.moveToNext()) {
                    events.add(
                        CalendarEventItem(
                            id = cursor.getLong(idIdx),
                            title = cursor.getString(titleIdx) ?: "Untitled Event",
                            startTime = cursor.getLong(startIdx),
                            endTime = cursor.getLong(endIdx),
                            description = cursor.getString(descIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            logger.e("CalendarCapability", "Error querying events: ${e.message}", e.message)
        }
        return events
    }

    override fun checkStatus(): CapabilityStatus {
        val granted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return if (granted) CapabilityStatus.Available else CapabilityStatus.PermissionRequired(listOf(android.Manifest.permission.READ_CALENDAR))
    }

    override fun release() {}
}
