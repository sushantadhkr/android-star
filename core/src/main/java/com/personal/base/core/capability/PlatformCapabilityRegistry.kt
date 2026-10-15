package com.personal.base.core.capability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe central registry managing all platform capability providers.
 * Core platform code and future plugins query capabilities through this registry.
 */
class PlatformCapabilityRegistry {

    private val providers = ConcurrentHashMap<CapabilityType, CapabilityProvider>()
    private val _capabilitiesState = MutableStateFlow<Map<CapabilityType, CapabilityStatus>>(emptyMap())
    val capabilitiesState: StateFlow<Map<CapabilityType, CapabilityStatus>> = _capabilitiesState.asStateFlow()

    /**
     * Registers a new or custom capability provider into the platform.
     */
    fun register(provider: CapabilityProvider) {
        providers[provider.type] = provider
        provider.initialize()
        refreshStatus()
    }

    /**
     * Unregisters a capability provider.
     */
    fun unregister(type: CapabilityType) {
        providers.remove(type)?.release()
        refreshStatus()
    }

    /**
     * Retrieves the provider instance for a specific capability type.
     */
    @Suppress("UNCHECKED_CAST")
    /**
     * Every registered provider.
     *
     * Exposed so the shell can ask each one what it wants shown, rather than keeping a list of
     * which capabilities deserve a tile — that list would be one more thing to forget to update.
     */
    fun allProviders(): Collection<CapabilityProvider> = providers.values

    fun <T : CapabilityProvider> getProvider(type: CapabilityType): T? {
        return providers[type] as? T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : CapabilityProvider> getProviderByClass(clazz: Class<T>): T? {
        return providers.values.firstOrNull { clazz.isInstance(it) } as? T
    }

    /**
     * Returns true if the capability is registered and currently in [CapabilityStatus.Available] state.
     */
    fun isAvailable(type: CapabilityType): Boolean {
        return providers[type]?.status?.isUsable == true
    }

    /**
     * Whether a provider is registered for this capability, regardless of its status.
     *
     * A capability can be registered yet unusable (for example a permission is missing). Callers
     * listing capabilities need to tell those cases apart.
     */
    fun isRegistered(type: CapabilityType): Boolean = providers.containsKey(type)

    /**
     * Every capability the platform declares, whether or not a provider is registered.
     */
    fun allTypes(): List<CapabilityType> = CapabilityType.entries.toList()

    /**
     * Returns current status for a given capability type.
     */
    fun getStatus(type: CapabilityType): CapabilityStatus {
        return providers[type]?.status ?: CapabilityStatus.Unavailable("Not registered in platform")
    }

    /**
     * Returns a snapshot of all registered capabilities and their current statuses.
     */
    fun getAllStatuses(): Map<CapabilityType, CapabilityStatus> {
        return providers.mapValues { it.value.status }
    }

    /**
     * Refreshes status across all registered providers.
     */
    fun refreshStatus() {
        val snapshot = providers.mapValues { it.value.status }
        _capabilitiesState.value = snapshot
    }

    /**
     * Releases all registered providers upon platform shutdown.
     */
    fun releaseAll() {
        providers.values.forEach { it.release() }
        providers.clear()
        refreshStatus()
    }
}
