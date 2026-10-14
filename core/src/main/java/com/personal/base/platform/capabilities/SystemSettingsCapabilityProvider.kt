package com.personal.base.platform.capabilities

import android.content.Context
import android.provider.Settings
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 18. SystemSettingsCapabilityProvider: read/write system and secure settings.
 */
class SystemSettingsCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SYSTEM_SETTINGS

    fun getSystemSetting(key: String): String? {
        return try {
            Settings.System.getString(context.contentResolver, key)
        } catch (_: Exception) { null }
    }

    fun getSecureSetting(key: String): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, key)
        } catch (_: Exception) { null }
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
