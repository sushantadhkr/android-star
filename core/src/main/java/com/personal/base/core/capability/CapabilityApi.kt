package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.EmbeddedServer
import com.personal.base.core.platform.ServerRequest
import com.personal.base.core.platform.ServerResponse
import com.personal.base.core.security.ApiTokenStoreContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The platform's HTTP surface: describes every capability, and drives the ones that expose actions.
 *
 * Two rules govern everything here:
 *
 *  1. **The gate is authoritative.** A capability the user has switched off is never invoked, no
 *     matter who asks. The capability catalogue reflects the same gate, so what you read is what
 *     you can do.
 *  2. **Authentication is mandatory.** The server can post notifications, run hardware and change
 *     settings, so every request must present the bearer token.
 */
class CapabilityApi(
    private val registry: PlatformCapabilityRegistry,
    private val gate: CapabilityGateContract,
    private val catalogue: CapabilityCatalogue,
    private val tokenStore: ApiTokenStoreContract,
    private val actions: CapabilityActions,
    private val logger: PlatformLogger
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Rejects a request that does not carry the current bearer token. */
    fun authorize(request: ServerRequest): ServerResponse? {
        if (tokenStore.matches(request.bearerToken())) return null
        logger.w("CapabilityApi", "Rejected unauthenticated request to ${request.path}")
        return ServerResponse(
            status = 401,
            contentType = "application/json",
            body = """{"error":"Unauthorized","hint":"Send 'Authorization: Bearer <token>'"}"""
        )
    }

    fun register(server: EmbeddedServer) {
        catalogue.refresh()

        server.registerRoute("/api/capabilities") { handleList() }
        server.registerRoute("/api/capabilities/status") { handleList() }

        // Drive a capability. The gate is checked here, before any action runs.
        server.registerRoute("/api/capabilities/invoke") { handleInvoke(it) }

        // Per-capability switch, and the master switch.
        server.registerRoute("/api/capabilities/toggle") { handleToggle(it) }
    }

    /**
     * Invokes a capability, enforcing the gate.
     *
     * Exposed so the gate rule can be tested without a socket. The gate is consulted before the
     * action is dispatched, so a switched-off capability is never touched.
     */
    fun handleInvoke(request: ServerRequest): ServerResponse {
        val payload = runCatching { json.parseToJsonElement(request.body) }.getOrNull()
            as? JsonObject
            ?: return ServerResponse(400, "application/json", """{"error":"Expected a JSON body"}""")

        val id = (payload["capability"] as? JsonPrimitive)?.content
            ?: return ServerResponse(
                400, "application/json",
                """{"error":"Missing 'capability' field","example":{"capability":"DEVICE_ACTIONS","action":"haptic","durationMs":200}}"""
            )

        val type = CapabilityType.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
            ?: return ServerResponse(
                404, "application/json",
                """{"error":"No such capability '$id'"}"""
            )

        // The gate decides. A switched-off capability is refused before it is ever touched.
        if (!gate.isGloballyEnabled()) {
            return ServerResponse(
                403, "application/json",
                """{"error":"Capabilities API is switched off"}"""
            )
        }
        if (!gate.isEnabled(type)) {
            return ServerResponse(
                403, "application/json",
                """{"error":"Capability '$id' is switched off"}"""
            )
        }
        if (!registry.isRegistered(type)) {
            return ServerResponse(
                409, "application/json",
                """{"error":"Capability '$id' has no provider on this device"}"""
            )
        }

        val action = (payload["action"] as? JsonPrimitive)?.content
            ?: return ServerResponse(
                400, "application/json",
                """{"error":"Missing 'action' field"}"""
            )

        return actions.invoke(type, action, payload)
    }

    private fun handleList(): ServerResponse =
        ServerResponse.json(json.encodeToString(catalogueSnapshot()))

    private fun handleToggle(request: ServerRequest): ServerResponse {
        val payload = runCatching { json.parseToJsonElement(request.body) }.getOrNull()
            as? JsonObject
            ?: return ServerResponse(400, "application/json", """{"error":"Expected a JSON body"}""")

        val enabled = (payload["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: return ServerResponse(400, "application/json", """{"error":"Missing boolean 'enabled'"}""")

        val id = (payload["capability"] as? JsonPrimitive)?.content

        if (id == null) {
            catalogue.setMaster(enabled)
            return ServerResponse.json("""{"masterEnabled":$enabled}""")
        }

        val type = CapabilityType.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
            ?: return ServerResponse(404, "application/json", """{"error":"No such capability '$id'"}""")

        catalogue.setCapability(type, enabled)
        return ServerResponse.json("""{"capability":"${type.name}","enabled":$enabled}""")
    }

    private fun catalogueSnapshot(): JsonObject = buildJsonObject {
        put("masterEnabled", catalogue.masterEnabled.value)
        put("count", catalogue.entries.value.size)
        put(
            "capabilities",
            buildJsonArray {
                catalogue.entries.value.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("id", entry.type.name)
                            put("displayName", entry.type.displayName)
                            put("description", entry.type.description)
                            put("registered", entry.isRegistered)
                            put("switchedOn", entry.isSwitchedOn)
                            put("usable", entry.isUsable)
                            put("status", statusName(entry.status))
                            put(
                                "actions",
                                buildJsonArray {
                                    CapabilityActions.actionsFor(entry.type).forEach { add(JsonPrimitive(it)) }
                                }
                            )
                            put(
                                "requiredPermissions",
                                buildJsonArray {
                                    entry.type.requiredPermissions.forEach { add(JsonPrimitive(it)) }
                                }
                            )
                        }
                    )
                }
            }
        )
    }

    private fun statusName(status: CapabilityStatus): String = when (status) {
        is CapabilityStatus.Available -> "available"
        is CapabilityStatus.Unavailable -> "unavailable"
        is CapabilityStatus.PermissionRequired -> "permission_required"
        is CapabilityStatus.DisabledByUser -> "disabled_by_user"
    }
}
