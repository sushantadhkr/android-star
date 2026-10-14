package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.ServerRequest
import com.personal.base.core.security.ApiTokenStoreContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the two rules the HTTP surface depends on:
 *  1. a request without the bearer token is refused;
 *  2. a capability the user switched off is never invoked, whatever the caller asks for.
 */
class CapabilityApiTest {

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

    private class StubTokenStore(private val token: String) : ApiTokenStoreContract {
        override fun token() = token
        override fun rotate() = token
        override fun matches(candidate: String?) = candidate == token
    }

    private lateinit var registry: PlatformCapabilityRegistry
    private lateinit var gate: FakeGate
    private lateinit var catalogue: CapabilityCatalogue
    private lateinit var api: CapabilityApi

    @Before
    fun setUp() {
        registry = PlatformCapabilityRegistry()
        gate = FakeGate()
        catalogue = CapabilityCatalogue(registry, gate)
        catalogue.refresh()

        api = CapabilityApi(
            registry = registry,
            gate = gate,
            catalogue = catalogue,
            tokenStore = StubTokenStore(TOKEN),
            actions = CapabilityActions(registry, PlatformLogger()),
            logger = PlatformLogger()
        )
    }

    private fun request(body: String, token: String? = TOKEN, scheme: String = "Bearer") =
        ServerRequest(
            method = "POST",
            path = "/api/capabilities/invoke",
            body = body,
            headers = if (token == null) emptyMap()
            else mapOf("Authorization" to "$scheme $token")
        )

    // ---- authentication ------------------------------------------------

    @Test
    fun `a request with the correct token passes`() {
        assertNull(api.authorize(request("{}")))
    }

    @Test
    fun `a request without a token is refused`() {
        assertEquals(401, api.authorize(request("{}", token = null))!!.status)
    }

    @Test
    fun `a request with the wrong token is refused`() {
        assertEquals(401, api.authorize(request("{}", token = "nope"))!!.status)
    }

    @Test
    fun `a non-bearer authorization header is refused`() {
        assertEquals(401, api.authorize(request("{}", scheme = "Basic"))!!.status)
    }

    @Test
    fun `the bearer scheme is matched case-insensitively`() {
        assertNull(api.authorize(request("{}", scheme = "bearer")))
    }

    // ---- the gate is authoritative -------------------------------------

    @Test
    fun `a disabled capability is refused before it is invoked`() {
        gate.setEnabled(CapabilityType.DEVICE_ACTIONS, false)
        val response =
            api.handleInvoke(request("""{"capability":"DEVICE_ACTIONS","action":"haptic"}"""))
        assertEquals(403, response.status)
        assertTrue(response.body.contains("switched off"))
    }

    @Test
    fun `the master switch off refuses every capability`() {
        gate.setGloballyEnabled(false)
        val response =
            api.handleInvoke(request("""{"capability":"DEVICE_ACTIONS","action":"haptic"}"""))
        assertEquals(403, response.status)
        assertTrue(response.body.contains("switched off"))
    }

    @Test
    fun `an unknown capability is a 404 not a silent success`() {
        val response = api.handleInvoke(request("""{"capability":"NOT_REAL","action":"x"}"""))
        assertEquals(404, response.status)
    }

    @Test
    fun `a capability with no provider is reported honestly`() {
        // Nothing is registered here, so invoking must not pretend to succeed.
        val response =
            api.handleInvoke(request("""{"capability":"DEVICE_ACTIONS","action":"haptic"}"""))
        assertEquals(409, response.status)
    }

    @Test
    fun `a malformed body is rejected rather than dispatched`() {
        assertEquals(400, api.handleInvoke(request("not json")).status)
        assertEquals(400, api.handleInvoke(request("""{"action":"haptic"}""")).status)
    }

    // ---- honesty about what actually works ----------------------------

    @Test
    fun `a capability with no callable action says so rather than failing obscurely`() {
        val response = api.handleInvoke(request("""{"capability":"DEVICE_ACTIONS","action":"nope"}"""))
        // Unregistered in this test, so the honest answer is "no provider", not a fake success.
        assertEquals(409, response.status)
    }

    @Test
    fun `disabled capabilities stay listed rather than disappearing`() {
        gate.setEnabled(CapabilityType.CAMERA, false)
        catalogue.refresh()
        assertTrue(catalogue.entries.value.any { it.type == CapabilityType.CAMERA })
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
