package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.RestrictedPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SMS is AppOps-managed, and the permission lives behind a screen the runtime dialog cannot reach.
 *
 * A wrong operation name here is the worst kind of bug: the platform reports "denied" forever,
 * the user has genuinely granted everything they can find, and nothing says why. These tests pin
 * the names down so that cannot happen quietly again.
 */
class RestrictedPermissionsTest {

    @Test
    fun `SMS and call log permissions are recognised as AppOps managed`() {
        listOf(
            "android.permission.READ_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_WAP_PUSH",
            "android.permission.RECEIVE_MMS",
            "android.permission.READ_CELL_BROADCASTS",
            "android.permission.READ_CALL_LOG",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS",
        ).forEach {
            assertTrue("$it should be AppOps managed", RestrictedPermissions.isAppOpsManaged(it))
        }
    }

    @Test
    fun `ordinary runtime permissions are not treated as AppOps managed`() {
        listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_CONTACTS",
            "android.permission.ACCESS_FINE_LOCATION",
        ).forEach {
            assertTrue("$it is a normal runtime permission", !RestrictedPermissions.isAppOpsManaged(it))
        }
    }

    @Test
    fun `an unrecognised permission is not AppOps managed`() {
        assertTrue(!RestrictedPermissions.isAppOpsManaged("android.permission.MADE_UP"))
        assertTrue(!RestrictedPermissions.isAppOpsManaged(""))
    }

    @Test
    fun `SMS capabilities are reachable through the API rather than hidden`() {
        // SMS used to have a provider but no actions, so a denial could not be reproduced or
        // diagnosed over HTTP at all.
        val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())
        assertEquals(listOf("count", "list", "read"), actions.actionsFor(CapabilityType.SMS).sorted())
    }

    @Test
    fun `every SMS action is documented`() {
        val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())
        val spec = actions.specFor(CapabilityType.SMS)!!

        spec.actions.forEach { action ->
            assertTrue(action.summary.isNotBlank())
            assertTrue(
                "${action.name} must ship a parseable example",
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(action.example) }.isSuccess
            )
        }
    }
}