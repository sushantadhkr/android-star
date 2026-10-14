package com.personal.base.platform.capabilities

import android.content.Context
import android.content.pm.PackageManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager

class CameraMicCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val isAudio: Boolean,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = if (isAudio) CapabilityType.MICROPHONE else CapabilityType.CAMERA

    override val status: CapabilityStatus
        get() {
            val pm = context.packageManager
            val feature = if (isAudio) PackageManager.FEATURE_MICROPHONE else PackageManager.FEATURE_CAMERA_ANY

            if (!pm.hasSystemFeature(feature)) {
                return CapabilityStatus.Unavailable(if (isAudio) "Microphone hardware not found" else "Camera hardware not found")
            }

            val missing = permissionManager.getMissingPermissions(type)
            if (missing.isNotEmpty()) {
                return CapabilityStatus.PermissionRequired(missing)
            }

            return CapabilityStatus.Available
        }

    override fun initialize() {
        logger.i("HardwareChecker", "Initialized check for ${type.displayName}: status=$status")
    }

    override fun release() {}
}
