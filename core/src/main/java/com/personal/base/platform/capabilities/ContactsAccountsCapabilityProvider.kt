package com.personal.base.platform.capabilities

import android.accounts.AccountManager
import android.content.Context
import android.provider.ContactsContract
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager

class ContactsAccountsCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.CONTACTS_ACCOUNTS

    override val status: CapabilityStatus
        get() {
            val missing = permissionManager.getMissingPermissions(type)
            return if (missing.isNotEmpty()) CapabilityStatus.PermissionRequired(missing)
            else CapabilityStatus.Available
        }

    override fun initialize() {
        logger.i("ContactsProvider", "Contacts & Accounts capability provider initialized. Status=$status")
    }

    override fun release() {}

    fun getContactsCount(): Int {
        if (!status.isUsable) return 0
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )
            val count = cursor?.count ?: 0
            cursor?.close()
            count
        } catch (e: Exception) {
            logger.w("ContactsProvider", "Failed to query contacts", e.message)
            0
        }
    }

    fun getAccountsCount(): Int {
        if (!status.isUsable) return 0
        return try {
            val am = AccountManager.get(context)
            am.accounts.size
        } catch (e: Exception) {
            0
        }
    }
}
