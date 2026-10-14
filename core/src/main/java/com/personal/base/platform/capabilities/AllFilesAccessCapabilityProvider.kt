package com.personal.base.platform.capabilities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 42. AllFilesAccessCapabilityProvider: MANAGE_EXTERNAL_STORAGE full filesystem access.
 */
class AllFilesAccessCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.ALL_FILES_ACCESS

    val isExternalStorageManager: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true

    fun promptManageStoragePermissionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !isExternalStorageManager) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else null
    }

    override fun checkStatus(): CapabilityStatus {
        return if (isExternalStorageManager) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("MANAGE_EXTERNAL_STORAGE not granted")
    }

    override fun release() {}
}
