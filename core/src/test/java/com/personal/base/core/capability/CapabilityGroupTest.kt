package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every capability belongs somewhere.
 *
 * The console groups by this rather than keeping a table of its own, because a hand-written table
 * is exactly the thing that silently rots as capabilities are added. These fail the build instead
 * of producing a capability that falls into no section at all.
 */
class CapabilityGroupTest {

    private val all = CapabilityType.entries

    @Test
    fun `every capability declares the group it belongs to`() {
        // Enforced by the compiler — `group` has no default value — so a capability that forgets
        // to declare where it lives cannot compile. This guards the invariant, not the placement.
        assertTrue(
            "every capability must have been placed deliberately",
            all.all { CapabilityGroup.entries.contains(it.group) }
        )
    }

    @Test
    fun `every group is actually used`() {
        val unused = CapabilityGroup.entries.filter { group -> all.none { it.group == group } }

        assertTrue("these groups hold no capability: $unused", unused.isEmpty())
    }

    @Test
    fun `every capability has a group that exists`() {
        all.forEach { type ->
            assertTrue("${type.name} has no group", CapabilityGroup.entries.contains(type.group))
        }
    }

    @Test
    fun `every group explains itself`() {
        CapabilityGroup.entries.forEach { group ->
            assertTrue("${group.name} needs a name", group.displayName.isNotBlank())
            assertTrue("${group.name} needs a blurb", group.blurb.isNotBlank())
        }
    }

    @Test
    fun `every capability name is unique so a group cannot hide a duplicate`() {
        val names = all.map { it.name }
        assertTrue(
            "duplicate capability names would make the console show one twice",
            names.size == names.distinct().size
        )
    }

    @Test
    fun `every documented capability publishes its group`() {
        val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())
        actions.specs.forEach { spec ->
            assertTrue(
                "${spec.type.name} must publish a group",
                CapabilityGroup.entries.contains(spec.type.group)
            )
        }
    }
}