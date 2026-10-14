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
import java.util.concurrent.ConcurrentHashMap

private const val VERDICT_TTL_MS = 30_000L

/**
 * Centralized manager for querying and evaluating system permissions across Android versions.
 */
class PlatformPermissionManager(
    private val context: Context,
    private val logger: PlatformLogger
) {

    private val _permissionsState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val permissionsState: StateFlow<Map<String, Boolean>> = _permissionsState.asStateFlow()

    /**
     * How a permission that cannot be inspected is proven instead.
     *
     * SMS and Call history are granted through a screen the app cannot reach, and their state
     * cannot be read back — [RestrictedPermissions] explains why every obvious way fails. A
     * provider that needs one registers a probe that performs the operation and reports whether it
     * worked. That is the only answer that cannot be a lie.
     */
    private val probes = ConcurrentHashMap<String, () -> Boolean>()

    private val verdicts = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /**
     * Registers how to demonstrate that a permission is really granted.
     *
     * The verdict is cached briefly, because a probe does real I/O and the capability catalogue
     * asks about the same permission repeatedly while a screen is open.
     */
    fun registerProbe(permission: String, probe: () -> Boolean) {
        probes[permission] = probe
        verdicts.remove(permission)
        logger.d("PermissionManager", "Registered a probe for $permission")
    }

    init {
        refreshPermissions()
    }

    /**
     * Checks if a single runtime permission is granted.
     *
     * Ordinary permissions answer themselves. SMS and Call history cannot be read back, so they are
     * answered by a registered [probe]. With no probe, they are reported as not granted — assuming
     * a permission that cannot be verified is exactly how a capability claims to work and does not.
     */
    fun isPermissionGranted(permission: String): Boolean {
        probes[permission]?.let { return cachedVerdict(permission, it) }

        if (RestrictedPermissions.isAppOpsManaged(permission)) {
            logger.d(
                "PermissionManager",
                "$permission cannot be read back and has no probe; reporting it as not granted"
            )
            return false
        }

        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun cachedVerdict(permission: String, probe: () -> Boolean): Boolean {
        val now = System.currentTimeMillis()
        val cached = verdicts[permission]
        if (cached != null && now - cached.second < VERDICT_TTL_MS) return cached.first

        val granted = runCatching { probe() }.getOrElse {
            logger.w("PermissionManager", "Probe for $permission threw: ${it.message}")
            false
        }
        verdicts[permission] = granted to now
        return granted
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
