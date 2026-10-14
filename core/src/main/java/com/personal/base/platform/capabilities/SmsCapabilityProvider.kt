package com.personal.base.platform.capabilities

import android.content.Context
import android.net.Uri
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager
import com.personal.base.platform.permissions.RestrictedPermissions

/**
 * Reading the SMS inbox.
 *
 * Read-only by design. Sending is a different permission with different consequences, and this
 * capability is declared as *reading* SMS, so it does not quietly claim to do more.
 */
class SmsCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SMS

    override val status: CapabilityStatus
        get() {
            val missing = permissionManager.getMissingPermissions(type)
            return if (missing.isNotEmpty()) {
                // Say *why* it is blocked. "Denied" on its own is useless to someone who has
                // already granted everything they could find in settings.
                CapabilityStatus.PermissionRequired(
                    missing.map { permission ->
                        val mode = RestrictedPermissions.modeFor(context, permission)
                        "$permission (app ops: $mode — grant from ${RestrictedPermissions.settingsLabel()})"
                    }
                )
            } else {
                CapabilityStatus.Available
            }
        }

    override fun initialize() {
        logger.i("SmsProvider", "SMS capability provider initialized. Status=$status")
    }

    override fun release() {}

    private fun inbox(): Uri = Uri.parse("content://sms/inbox")

    fun getSmsCount(): Int = list(limit = 1).let { countOf(it) }

    /**
     * Recent inbox messages, newest first.
     *
     * Returns null when the read was refused, so a refusal is never mistaken for an empty inbox —
     * the difference matters enormously when deciding whether a phone has messages.
     */
    fun list(limit: Int = 20, search: String? = null): List<SmsMessage>? {
        if (!permissionManager.isPermissionGranted(android.Manifest.permission.READ_SMS)) return null

        return try {
            context.contentResolver.query(
                inbox(),
                arrayOf("_id", "address", "body", "date", "type", "read"),
                null,
                null,
                "date DESC LIMIT ${limit.coerceIn(1, 200)}"
            )?.use { cursor ->
                val indexId = cursor.getColumnIndex("_id")
                val indexAddress = cursor.getColumnIndex("address")
                val indexBody = cursor.getColumnIndex("body")
                val indexDate = cursor.getColumnIndex("date")
                val indexType = cursor.getColumnIndex("type")
                val indexRead = cursor.getColumnIndex("read")

                buildList {
                    while (cursor.moveToNext()) {
                        val body = cursor.getStringOrNull(indexBody).orEmpty()
                        val address = cursor.getStringOrNull(indexAddress).orEmpty()
                        if (search != null &&
                            !body.contains(search, ignoreCase = true) &&
                            !address.contains(search, ignoreCase = true)
                        ) continue

                        add(
                            SmsMessage(
                                id = cursor.getLongOrZero(indexId),
                                address = address,
                                body = body,
                                timestampMillis = cursor.getLongOrZero(indexDate),
                                type = if (cursor.getIntOrZero(indexType) == MESSAGE_TYPE_INBOX) {
                                    "inbox"
                                } else {
                                    "other"
                                },
                                read = cursor.getIntOrZero(indexRead) == 1
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            logger.w("SmsProvider", "SMS read refused: ${e.message}")
            null
        } catch (e: Exception) {
            logger.e("SmsProvider", "Failed to read SMS", e.message)
            null
        }
    }

    /** One message, or null when there is no such message or the read was refused. */
    fun read(id: Long): SmsMessage? =
        list(limit = 200).orEmpty().firstOrNull { it.id == id }

    /** How many messages the inbox holds, or null when the count could not be taken. */
    fun count(): Int? {
        if (!permissionManager.isPermissionGranted(android.Manifest.permission.READ_SMS)) return null
        return try {
            context.contentResolver.query(inbox(), arrayOf("_id"), null, null, null)?.use { it.count }
        } catch (e: Exception) {
            logger.w("SmsProvider", "SMS count failed: ${e.message}")
            null
        }
    }

    private fun countOf(messages: List<SmsMessage>?): Int = count() ?: messages?.size ?: 0

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun android.database.Cursor.getLongOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun android.database.Cursor.getIntOrZero(index: Int): Int =
        if (index >= 0 && !isNull(index)) getInt(index) else 0

    private companion object {
        const val MESSAGE_TYPE_INBOX = 1
    }
}

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val timestampMillis: Long,
    val type: String,
    val read: Boolean
)