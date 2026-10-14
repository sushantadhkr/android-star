package com.personal.base.platform.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized manager for querying and evaluating system permissions across Android versions.
 */
class PlatformPermissionManager(
    private val context: Context,
    private val logger: PlatformLogger
) {

    private val _permissionsState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val permissionsState: StateFlow<Map<String, Boolean>> = _permissionsState.asStateFlow()

    init {
        refreshPermissions()
    }

    /**
     * Checks if a single runtime permission is granted.
     *
     * SMS and Call Log permissions are special: since Android 10 they are AppOps-managed, so
     * `checkSelfPermission` always reports them denied even once the user has granted them. Those
     * are queried through AppOps instead, otherwise a granted permission still looks blocked.
     */
    fun isPermissionGranted(permission: String): Boolean {
        if (RestrictedPermissions.requiresSpecialAccess(permission)) {
            return RestrictedPermissions.isGranted(context, permission)
        }
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Whether a permission must be granted from system settings rather than a runtime dialog.
     */
    fun requiresSpecialAccess(permission: String): Boolean =
        RestrictedPermissions.requiresSpecialAccess(permission)

    /**
     * The permissions of a capability that cannot be granted through a runtime dialog.
     */
    fun getSpecialAccessPermissions(capability: CapabilityType): List<String> =
        getPermissionsForCapability(capability).filter { requiresSpecialAccess(it) }

    /**
     * Checks if all required permissions for a capability are granted.
     */
    fun arePermissionsGrantedFor(capability: CapabilityType): Boolean {
        val permissions = getPermissionsForCapability(capability)
        return permissions.all { isPermissionGranted(it) }
    }

    /**
     * Gets the missing permissions for a capability.
     */
    fun getMissingPermissions(capability: CapabilityType): List<String> {
        val permissions = getPermissionsForCapability(capability)
        return permissions.filter { !isPermissionGranted(it) }
    }

    /**
     * Returns the list of runtime permissions needed by a capability.
     */
    fun getPermissionsForCapability(capability: CapabilityType): List<String> {
        return capability.requiredPermissions
    }

    /**
     * Refreshes the cached permission states across all capabilities.
     */
    fun refreshPermissions() {
        val allPermissions = CapabilityType.values().flatMap { it.requiredPermissions }.distinct()

        val snapshot = allPermissions.associateWith { isPermissionGranted(it) }
        _permissionsState.value = snapshot
        logger.d("PermissionManager", "Refreshed permissions snapshot. Granted: ${snapshot.filterValues { it }.keys.size}/${allPermissions.size}")
    }
}
