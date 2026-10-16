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

    /**
     * Providers declared but not yet built.
     *
     * Registration is the thing that made every process start expensive. Android restarts the app
     * to deliver a single notification, and an eager registry meant that one notification cost 62
     * object constructions, a network callback, three timers and a capability refresh — measured at
     * 262 ms of CPU before the service could even look at the notification.
     *
     * Declaring a factory instead costs one lambda. The provider is built the first time something
     * genuinely asks for it, which is the only moment its construction is worth paying for.
     */
    private val factories = ConcurrentHashMap<CapabilityType, () -> CapabilityProvider>()

    private val _capabilitiesState = MutableStateFlow<Map<CapabilityType, CapabilityStatus>>(emptyMap())
    val capabilitiesState: StateFlow<Map<CapabilityType, CapabilityStatus>> = _capabilitiesState.asStateFlow()

    /**
     * Declares a provider without building it.
     *
     * The factory runs at most once, on first use. Re-declaring a type replaces the factory, which
     * is what [register] does for an already-built provider.
     */
    fun declare(type: CapabilityType, factory: () -> CapabilityProvider) {
        factories[type] = factory
    }

    /**
     * Builds and registers a provider immediately, bypassing laziness.
     *
     * Kept for the handful of places that genuinely need an instance to exist, and for tests, where
     * an unbuilt provider would make an assertion quietly vacuous.
     */
    fun register(provider: CapabilityProvider) {
        providers[provider.type] = provider
        factories.remove(provider.type)
        provider.initialize()
        refreshStatus()
    }

    /** How many providers are declared, built or not. Cheaper than materialising them all. */
    fun declaredCount(): Int = factories.size + providers.size

    /** Builds every declared provider that has not been built yet. */
    fun materialiseAll() {
        factories.keys.toList().forEach { resolve(it) }
    }

    /**
     * Returns the provider for [type], building it on first use.
     *
     * Returns null when nothing was declared for the type, which is what a 409 "no provider on this
     * device" should mean.
     */
    private fun resolve(type: CapabilityType): CapabilityProvider? {
        providers[type]?.let { return it }
        val factory = factories[type] ?: return null
        val built = factory()
        // Put the instance back before publishing, so two threads racing for the same capability
        // cannot both construct it and leak the loser's listeners.
        providers[type] = built
        built.initialize()
        return built
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
    fun allProviders(): Collection<CapabilityProvider> {
        // Asking for every provider means every provider is needed, so this builds them all. Callers
        // are the widget publisher and the catalogue, both of which genuinely want the full set.
        materialiseAll()
        return providers.values
    }

    fun <T : CapabilityProvider> getProvider(type: CapabilityType): T? {
        @Suppress("UNCHECKED_CAST")
        return resolve(type) as? T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : CapabilityProvider> getProviderByClass(clazz: Class<T>): T? {
        materialiseAll()
        return providers.values.firstOrNull { clazz.isInstance(it) } as? T
    }

    /**
     * Returns true if the capability is registered and currently in [CapabilityStatus.Available] state.
     */
    fun isAvailable(type: CapabilityType): Boolean {
        return resolve(type)?.status?.isUsable == true
    }

    /**
     * Whether a provider is registered for this capability, regardless of its status.
     *
     * A capability can be registered yet unusable (for example a permission is missing). Callers
     * listing capabilities need to tell those cases apart.
     */
    fun isRegistered(type: CapabilityType): Boolean =
        providers.containsKey(type) || factories.containsKey(type)

    /**
     * Every capability the platform declares, whether or not a provider is registered.
     */
    fun allTypes(): List<CapabilityType> = CapabilityType.entries.toList()

    /**
     * Returns current status for a given capability type.
     */
    fun getStatus(type: CapabilityType): CapabilityStatus {
        return resolve(type)?.status ?: CapabilityStatus.Unavailable("Not registered in platform")
    }

    /**
     * Returns a snapshot of all registered capabilities and their current statuses.
     */
    fun getAllStatuses(): Map<CapabilityType, CapabilityStatus> {
        materialiseAll()
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
