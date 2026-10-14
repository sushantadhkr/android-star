package com.personal.base.core.capability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One row of the capability catalogue: a capability the platform knows about, together with
 * everything the UI needs to decide whether it is usable.
 */
data class CapabilityEntry(
    val type: CapabilityType,
    val isRegistered: Boolean,
    val status: CapabilityStatus,
    val isSwitchedOn: Boolean,
    val masterEnabled: Boolean
) {
    /** Usable only when the master switch, the individual switch, registration and status all agree. */
    val isUsable: Boolean
        get() = masterEnabled && isSwitchedOn && isRegistered && status.isUsable

    /**
     * Listed but not usable. The capability is always shown — never hidden — and simply reads as
     * unavailable, whether because it is unregistered, switched off, or missing a permission.
     */
    val isBlocked: Boolean get() = !isUsable
}

/**
 * Builds the complete capability catalogue.
 *
 * Every capability the platform declares appears, including those with no provider or no granted
 * permission. A capability is never omitted from the list merely because it cannot currently be
 * used — it is shown as unavailable instead.
 */
class CapabilityCatalogue(
    private val registry: PlatformCapabilityRegistry,
    private val gate: CapabilityGateContract
) {

    private val _entries = MutableStateFlow<List<CapabilityEntry>>(emptyList())
    val entries: StateFlow<List<CapabilityEntry>> = _entries.asStateFlow()

    private val _masterEnabled = MutableStateFlow(true)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    fun refresh() {
        _masterEnabled.value = gate.isGloballyEnabled()
        _entries.value = CapabilityType.entries.map { type ->
            CapabilityEntry(
                type = type,
                isRegistered = registry.isRegistered(type),
                status = registry.getStatus(type),
                isSwitchedOn = gate.isEnabled(type),
                masterEnabled = gate.isGloballyEnabled()
            )
        }
    }

    fun setMaster(enabled: Boolean) {
        gate.setGloballyEnabled(enabled)
        refresh()
    }

    fun setCapability(type: CapabilityType, enabled: Boolean) {
        gate.setEnabled(type, enabled)
        refresh()
    }
}