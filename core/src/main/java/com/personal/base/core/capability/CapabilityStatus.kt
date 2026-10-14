package com.personal.base.core.capability

/**
 * Represents the current operational availability of a capability.
 */
sealed interface CapabilityStatus {
    /** The capability is ready for immediate invocation. */
    data object Available : CapabilityStatus

    /** The capability hardware or system service is not available on this device. */
    data class Unavailable(val reason: String) : CapabilityStatus

    /** The capability requires one or more runtime permissions that are currently denied. */
    data class PermissionRequired(val missingPermissions: List<String>) : CapabilityStatus

    /** The capability is available, but currently disabled by user platform policy. */
    data class DisabledByUser(val policyReason: String) : CapabilityStatus

    val isUsable: Boolean
        get() = this is Available
}

/**
 * Common base interface for all modular capability providers.
 */
interface CapabilityProvider {
    /** The type of capability provided. */
    val type: CapabilityType

    /** Current capability status. Defaults to checking checkStatus() dynamically. */
    val status: CapabilityStatus
        get() = checkStatus()

    /**
     * Initializes the provider and establishes necessary listeners.
     */
    fun initialize() {}

    /**
     * Releases system resources, listeners, or held locks.
     */
    fun release() {}

    /**
     * Optional method for providers that implement checkStatus dynamically.
     */
    fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
}
