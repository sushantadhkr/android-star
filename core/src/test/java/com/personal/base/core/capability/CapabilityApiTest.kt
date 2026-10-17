package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.ServerRequest
import com.personal.base.core.security.ApiTokenStoreContract
import kotlinx.serialization.json.buildJsonObject
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

    // ---- the gate holds on paths that are not HTTP -----------------------

    @Test
    fun `the gate is checked for callers that never touch the API`() {
        gate.setEnabled(CapabilityType.DEVICE_ACTIONS, false)

        // A notification button runs through the same door. The switch the user flipped has to mean
        // the same thing whoever is asking, and that includes the user's own thumb on a button.
        val response = api.invokeGated(CapabilityType.DEVICE_ACTIONS, "haptic", buildJsonObject {})

        assertEquals(403, response.status)
        assertTrue(response.body.contains("switched off"))
    }

    @Test
    fun `the master switch also covers non-API callers`() {
        gate.setGloballyEnabled(false)
        val response = api.invokeGated(CapabilityType.DEVICE_ACTIONS, "haptic", buildJsonObject {})
        assertEquals(403, response.status)
    }

    @Test
    fun `a capability with no provider is refused for every caller`() {
        val response = api.invokeGated(CapabilityType.DEVICE_ACTIONS, "haptic", buildJsonObject {})
        assertEquals(409, response.status)
    }

    @Test
    fun `capability names resolve the same way whoever is asking`() {
        assertEquals(CapabilityType.NOTIFICATIONS, api.resolveType("notifications"))
        assertEquals(CapabilityType.NOTIFICATIONS, api.resolveType("NOTIFICATIONS"))
        assertNull(api.resolveType("NOT_REAL"))
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

    // ---- pulling a produced file ---------------------------------------

    /** Pulls and returns what went onto the wire, so the response can be read as bytes. */
    private fun pull(query: String): String {
        val out = java.io.ByteArrayOutputStream()
        val handled = api.handlePull(
            ServerRequest("GET", FILE_PULL_ROUTE, "", emptyMap(), "$FILE_PULL_ROUTE?$query"),
            out
        )
        assertTrue("the pull route must always answer, never decline", handled)
        return out.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun statusLine(response: String) = response.lineSequence().first()

    @Test
    fun `a pull for a disabled capability is refused, not streamed`() {
        // The whole point of the switch is that a capability you turned off cannot be reached by
        // any route. A file route that skipped the gate would quietly undo that.
        gate.setEnabled(CapabilityType.CAMERA, false)
        assertTrue(statusLine(pull("capability=CAMERA&id=cap_1")).contains("403"))
    }

    @Test
    fun `a pull with the master switch off is refused`() {
        gate.setGloballyEnabled(false)
        assertTrue(statusLine(pull("capability=DOWNLOAD_MANAGER&id=dl_1")).contains("403"))
    }

    @Test
    fun `a pull without a capability or id is a 400 not a guess`() {
        assertTrue(statusLine(pull("id=cap_1")).contains("400"))
        assertTrue(statusLine(pull("capability=CAMERA")).contains("400"))
    }

    @Test
    fun `a pull naming an unknown capability is a 404`() {
        assertTrue(statusLine(pull("capability=NOT_REAL&id=x")).contains("404"))
    }

    @Test
    fun `a pull from a capability that produces no files says so`() {
        // DEVICE_ACTIONS produces no files. Inventing a 200 here would be a fake success.
        assertTrue(statusLine(pull("capability=DEVICE_ACTIONS&id=x")).contains("404"))
    }

    @Test
    fun `a pull for an id nothing owns is a 404 rather than an empty file`() {
        // The failure that matters: an empty body with a 200 reads to a client as "here is your
        // file, it is zero bytes", which is worse than saying it is not there.
        val response = pull("capability=CAMERA&id=cap_does_not_exist")
        assertTrue(statusLine(response).contains("404"))
        assertTrue(response.contains("No file for id"))
    }

    @Test
    fun `a pull never accepts a path`() {
        // The only parameters are a capability and an id. There is no path parameter for a caller
        // to aim at the device's filesystem with, which is why traversal has nothing to work on.
        val withPath = pull("capability=CAMERA&path=/data/data/com.personal.base")
        assertTrue(statusLine(withPath).contains("400"))
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}
