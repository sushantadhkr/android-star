package com.personal.base.core.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the pinned Platform surface rules: every capability is listed even when unusable, and the
 * master switch governs them all.
 */
class CapabilityCatalogueTest {

    private class FakeGate : CapabilityGateContract {
        var master = true
        val off = mutableSetOf<CapabilityType>()

        override fun isGloballyEnabled() = master
        override fun setGloballyEnabled(enabled: Boolean) {
            master = enabled
        }

        override fun isEnabled(type: CapabilityType) = !off.contains(type)
        override fun setEnabled(type: CapabilityType, enabled: Boolean) {
            if (enabled) off.remove(type) else off.add(type)
        }
    }

    private lateinit var gate: FakeGate
    private lateinit var catalogue: CapabilityCatalogue

    @Before
    fun setUp() {
        val registry = PlatformCapabilityRegistry()
        gate = FakeGate()
        catalogue = CapabilityCatalogue(registry, gate)
        catalogue.refresh()
    }

    @Test
    fun `every declared capability is listed even without a provider`() {
        val listed = catalogue.entries.value.map { it.type }.toSet()
        assertEquals(CapabilityType.entries.toSet(), listed)
    }

    @Test
    fun `an unregistered capability is listed but blocked`() {
        val entry = catalogue.entries.value.first { it.type == CapabilityType.CAMERA }
        assertFalse("no provider is registered in this test", entry.isRegistered)
        assertTrue(entry.isBlocked)
    }

    @Test
    fun `master off blocks every capability`() {
        gate.setGloballyEnabled(false)
        catalogue.refresh()

        assertFalse(catalogue.masterEnabled.value)
        assertTrue(catalogue.entries.value.all { it.isBlocked })
    }

    @Test
    fun `individually switching off one capability blocks only it`() {
        gate.setEnabled(CapabilityType.CAMERA, false)
        catalogue.refresh()

        val camera = catalogue.entries.value.first { it.type == CapabilityType.CAMERA }
        assertFalse(camera.isSwitchedOn)
        assertTrue(camera.isBlocked)
    }

    @Test
    fun `listing is stable across refreshes`() {
        val first = catalogue.entries.value.map { it.type }
        catalogue.refresh()
        assertEquals(first, catalogue.entries.value.map { it.type })
    }

    @Test
    fun `nothing is ever dropped from the listing`() {
        gate.setGloballyEnabled(false)
        catalogue.refresh()
        assertEquals(CapabilityType.entries.size, catalogue.entries.value.size)
    }
}