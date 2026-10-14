package com.personal.base.platform.capabilities

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.plugin.PlatformLocation
import com.personal.base.platform.permissions.PlatformPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val logger: PlatformLogger
) : CapabilityProvider, LocationListener {

    override val type: CapabilityType = CapabilityType.LOCATION

    private var locationManager: LocationManager? = null
    private val _lastLocation = MutableStateFlow<PlatformLocation?>(null)
    val lastLocation: StateFlow<PlatformLocation?> = _lastLocation.asStateFlow()

    override val status: CapabilityStatus
        get() {
            val missing = permissionManager.getMissingPermissions(CapabilityType.LOCATION)
            if (missing.isNotEmpty()) {
                return CapabilityStatus.PermissionRequired(missing)
            }
            if (locationManager == null) {
                return CapabilityStatus.Unavailable("LocationManager service not available")
            }
            val hasGps = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            val hasNetwork = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            return if (hasGps || hasNetwork) {
                CapabilityStatus.Available
            } else {
                CapabilityStatus.Unavailable("Location providers (GPS/Network) disabled by system")
            }
        }

    override fun initialize() {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        logger.i("LocationProvider", "Location capability provider initialized")
        updateLastKnownLocation()
    }

    override fun release() {
        locationManager?.removeUpdates(this)
        logger.i("LocationProvider", "Location updates stopped")
    }

    @SuppressLint("MissingPermission")
    fun updateLastKnownLocation() {
        if (!permissionManager.arePermissionsGrantedFor(CapabilityType.LOCATION)) return
        val lm = locationManager ?: return

        try {
            val gpsLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = listOfNotNull(gpsLoc, netLoc).maxByOrNull { it.time }

            if (best != null) {
                _lastLocation.value = PlatformLocation(
                    latitude = best.latitude,
                    longitude = best.longitude,
                    accuracyMeters = best.accuracy,
                    provider = best.provider ?: "unknown",
                    timestamp = best.time
                )
            }
        } catch (e: Exception) {
            logger.w("LocationProvider", "Error reading last known location", e.message)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getFreshLocation(): PlatformLocation? {
        updateLastKnownLocation()
        _lastLocation.value?.let { return it }

        if (!permissionManager.arePermissionsGrantedFor(CapabilityType.LOCATION)) return null
        val lm = locationManager ?: return null

        return suspendCancellableCoroutine { continuation ->
            val singleListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    val result = PlatformLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracyMeters = location.accuracy,
                        provider = location.provider ?: "gps",
                        timestamp = location.time
                    )
                    _lastLocation.value = result
                    continuation.resume(result)
                }

                override fun onProviderDisabled(provider: String) {}
                override fun onProviderEnabled(provider: String) {}
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, singleListener, null)
                } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, singleListener, null)
                } else {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
            } catch (e: Exception) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                lm.removeUpdates(singleListener)
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        _lastLocation.value = PlatformLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            provider = location.provider ?: "gps",
            timestamp = location.time
        )
    }

    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
