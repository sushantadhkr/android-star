package com.personal.base.platform.capabilities

import android.content.Context
import android.location.Location
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import java.util.concurrent.ConcurrentHashMap

data class PlatformGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float
)

/**
 * 26. GeofenceActivityCapabilityProvider: geofencing and activity recognition transitions.
 */
class GeofenceActivityCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.GEOFENCE_ACTIVITY

    private val geofences = ConcurrentHashMap<String, PlatformGeofence>()

    fun registerGeofence(geofence: PlatformGeofence) {
        geofences[geofence.id] = geofence
        logger.i("GeofenceActivity", "Registered geofence: ${geofence.id} (${geofence.latitude}, ${geofence.longitude})")
    }

    fun removeGeofence(id: String) {
        geofences.remove(id)
    }

    fun checkLocationAgainstGeofences(location: Location): List<String> {
        val triggered = mutableListOf<String>()
        geofences.values.forEach { fence ->
            val results = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, fence.latitude, fence.longitude, results)
            if (results[0] <= fence.radiusMeters) {
                triggered.add(fence.id)
            }
        }
        return triggered
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
