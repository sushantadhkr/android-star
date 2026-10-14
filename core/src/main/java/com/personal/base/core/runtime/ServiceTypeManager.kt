package com.personal.base.core.runtime

import android.content.pm.ServiceInfo
import android.os.Build
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * Handles Android 14+ Foreground Service Types mapping per active capability.
 */
class ServiceTypeManager(
    private val logger: PlatformLogger
) {
    /**
     * Resolves bitmask of ServiceInfo.FOREGROUND_SERVICE_TYPE_* for a given capability.
     */
    fun getForegroundServiceTypeForCapability(capability: CapabilityType): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        return when (capability) {
            CapabilityType.LOCATION,
            CapabilityType.GEOFENCE_ACTIVITY -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            CapabilityType.CAMERA,
            CapabilityType.SCREEN_CAPTURE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            CapabilityType.MICROPHONE,
            CapabilityType.AUDIO_STREAM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            CapabilityType.NEARBY_DEVICES,
            CapabilityType.WIFI -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
            CapabilityType.MEDIA_SESSION -> {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            }
        }
    }
}
