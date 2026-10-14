package com.personal.base.core.capability

import com.personal.base.core.app.AppRoute
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
    private val logger: PlatformLogger,
    /** Supplied by the App manager so App routes appear in the docs from their own definitions. */
    private val appRoutes: () -> List<Pair<String, List<AppRoute>>> = { emptyList() }
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

        // Self-describing documentation, generated from the same specs that implement the actions.
        server.registerRoute("/api/capabilities/docs") { handleDocs() }

        // Drive a capability. The gate is checked here, before any action runs.
        server.registerRoute("/api/capabilities/invoke") { handleInvoke(it) }

        // Per-capability switch, and the master switch.
        server.registerRoute("/api/capabilities/toggle") { handleToggle(it) }
    }

    /**
     * Documentation for every endpoint and action.
     *
     * Rendered from [CapabilityActions.specs], so it is generated from the implementation and cannot
     * fall out of date.
     */
    private fun handleDocs(): ServerResponse = ServerResponse.json(
        buildJsonObject {
            put("title", "Android Star — Capabilities API")
            put("port", com.personal.base.core.platform.EmbeddedServer.DEFAULT_PORT)
            put(
                "authentication",
                "Send 'Authorization: Bearer <token>'. The token is shown in Settings → API access " +
                    "token and can be rotated there."
            )
            put("note", "A capability switched off is never invoked; requests get 403.")

            put("endpoints", buildJsonArray {
                endpoint("/api/capabilities", "GET", "List every capability with status and actions")
                endpoint("/api/capabilities/status", "GET", "Alias of /api/capabilities")
                endpoint("/api/capabilities/docs", "GET", "This document")
                endpoint("/api/capabilities/invoke", "POST", "Run a capability action")
                endpoint("/api/capabilities/toggle", "POST", "Switch a capability, or all of them")
                endpoint("/api/apps/<id>/<path>", "GET", "Endpoints published by Apps that serve")
            })

            put("invokeBody", buildJsonObject {
                put("capability", JsonPrimitive("CAPABILITY_ID"))
                put("action", JsonPrimitive("action name"))
            })

            put("toggleBody", buildJsonObject {
                put("capability", JsonPrimitive("CAPABILITY_ID or omit for the master switch"))
                put("enabled", JsonPrimitive(true))
            })

            put("appRoutes", appRoutesJson())

            put("capabilities", buildJsonArray {
                actions.specs.forEach { spec ->
                    add(
                        buildJsonObject {
                            put("id", spec.type.name)
                            put("displayName", spec.type.displayName)
                            put("description", spec.type.description)
                            put("summary", spec.summary)
                            put(
                                "requiredPermissions",
                                buildJsonArray {
                                    spec.type.requiredPermissions.forEach { add(JsonPrimitive(it)) }
                                }
                            )
                            put("actions", buildJsonArray {
                                spec.actions.forEach { action ->
                                    add(
                                        buildJsonObject {
                                            put("name", action.name)
                                            put("summary", action.summary)
                                            put("requiresUserConsent", action.requiresUserConsent)
                                            put("params", buildJsonArray {
                                                action.params.forEach { param ->
                                                    add(
                                                        buildJsonObject {
                                                            put("name", param.name)
                                                            put("type", param.type)
                                                            put("required", param.required)
                                                            put("description", param.description)
                                                            param.example?.let { put("example", it) }
                                                        }
                                                    )
                                                }
                                            })
                                            put("exampleRequest", action.example)
                                            put("exampleResponse", action.returns)
                                        }
                                    )
                                }
                            })
                        }
                    )
                }
            })
        }.toString()
    )

    private fun endpoint(path: String, method: String, summary: String) = buildJsonObject {
        put("path", path)
        put("method", method)
        put("summary", summary)
    }

    /**
     * The routes Apps have published.
     *
     * Sourced from the same [AppRoute] objects the Apps registered, so an App's documentation is
     * generated from its implementation rather than maintained alongside it.
     */
    private fun appRoutesJson(): kotlinx.serialization.json.JsonArray = buildJsonArray {
        appRoutes().forEach { (appId, routes) ->
            routes.forEach { route ->
                add(
                    buildJsonObject {
                        put("app", appId)
                        put("path", "/api/apps/$appId/${route.path}")
                        put("summary", route.summary)
                        put("requiresUserConsent", route.requiresUserConsent)
                        if (route.example.isNotBlank()) put("exampleRequest", route.example)
                    }
                )
            }
        }
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

        val action = (payload["action"] as? JsonPrimitive)?.content
            ?: return ServerResponse(
                400, "application/json",
                """{"error":"Missing 'action' field"}"""
            )

        return invokeGated(type, action, payload)
    }

    /**
     * The only way into a capability, whoever is asking.
     *
     * The gate lives here rather than at the HTTP route so that every caller obeys it — the API, a
     * notification button, an App. A capability the user switched off is never touched, which is
     * the whole point of the switch; checking it in one place is what makes that true rather than
     * merely intended.
     */
    fun invokeGated(type: CapabilityType, action: String, payload: JsonObject): ServerResponse {
        if (!gate.isGloballyEnabled()) {
            return ServerResponse(
                403, "application/json",
                """{"error":"Capabilities API is switched off"}"""
            )
        }
        if (!gate.isEnabled(type)) {
            return ServerResponse(
                403, "application/json",
                """{"error":"Capability '${type.name}' is switched off"}"""
            )
        }
        if (!registry.isRegistered(type)) {
            return ServerResponse(
                409, "application/json",
                """{"error":"Capability '${type.name}' has no provider on this device"}"""
            )
        }
        return actions.invoke(type, action, payload)
    }

    /**
     * Resolves a capability name, as written by a caller, to a real capability.
     *
     * Shared with paths that are not HTTP, so a button or an App cannot reach a capability the API
     * itself would refuse.
     */
    fun resolveType(id: String): CapabilityType? =
        CapabilityType.entries.firstOrNull { it.name.equals(id, ignoreCase = true) }

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
                                    actions.actionsFor(entry.type).forEach { add(JsonPrimitive(it)) }
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
