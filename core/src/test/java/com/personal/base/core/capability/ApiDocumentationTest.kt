package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The documentation convention, enforced.
 *
 * Every action the API exposes must be documented. `ActionSpec` already refuses to be constructed
 * without a summary and an example, so this test guards the parts it cannot: that a capability is
 * documented, that no action is undocumented, and that the catalogue and the docs agree.
 */
class ApiDocumentationTest {

    private val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())

    @Test
    fun `every exposed action has a summary`() {
        actions.specs.forEach { spec ->
            spec.actions.forEach { action ->
                assertTrue(
                    "${spec.type.name}.${action.name} needs a summary",
                    action.summary.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `every exposed action has a usage example`() {
        actions.specs.forEach { spec ->
            spec.actions.forEach { action ->
                assertTrue(
                    "${spec.type.name}.${action.name} needs an example",
                    action.example.contains("\"action\"")
                )
            }
        }
    }

    @Test
    fun `every documented parameter is explained`() {
        actions.specs.forEach { spec ->
            spec.actions.forEach { action ->
                action.params.forEach { param ->
                    assertTrue(
                        "${spec.type.name}.${action.name}.${param.name} needs a description",
                        param.description.isNotBlank()
                    )
                }
            }
        }
    }

    @Test
    fun `every capability with actions has a summary`() {
        actions.specs.forEach { spec ->
            assertTrue("${spec.type.name} needs a summary", spec.summary.isNotBlank())
        }
    }

    @Test
    fun `action names are unique within a capability`() {
        actions.specs.forEach { spec ->
            val names = spec.actions.map { it.name }
            assertEquals(
                "${spec.type.name} has duplicate action names",
                names.size,
                names.distinct().size
            )
        }
    }

    @Test
    fun `the catalogue advertises exactly the documented actions`() {
        actions.specs.forEach { spec ->
            assertEquals(
                "${spec.type.name} catalogue drifted from its docs",
                spec.names.sorted(),
                actions.actionsFor(spec.type).sorted()
            )
        }
    }

    @Test
    fun `capabilities with no implementation advertise nothing`() {
        // Advertising an action a provider cannot perform would be a lie.
        assertTrue(actions.actionsFor(CapabilityType.SIM_SUBSCRIPTION).isEmpty())
        assertTrue(actions.actionsFor(CapabilityType.CALL_LOG).isEmpty())
    }

    @Test
    fun `actions needing user consent say so`() {
        val mic = actions.specFor(CapabilityType.MICROPHONE)!!.action("record")!!
        assertTrue("microphone recording must declare consent", mic.requiresUserConsent)

        val haptic = actions.specFor(CapabilityType.DEVICE_ACTIONS)!!.action("haptic")!!
        assertTrue("haptics need no consent", !haptic.requiresUserConsent)
    }

    @Test
    fun `docs are served for every documented capability`() {
        assertTrue("no capabilities documented", actions.specs.isNotEmpty())
    }
}
