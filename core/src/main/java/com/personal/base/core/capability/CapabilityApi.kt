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

        // Bytes out. Raw because a photo or a finished download has no honest JSON form, and
        // it gates itself for the same reason every action does.
        server.registerRawRoute(FILE_PULL_ROUTE) { request, out -> handlePull(request, out) }

        // Per-capability switch, and the master switch.
        server.registerRoute("/api/capabilities/toggle") { handleToggle(it) }

        // The one shortcut: fetch a URL straight into the Downloads folder. It is a convenience
        // over DOWNLOAD_MANAGER.fetch for the one call someone makes most often, and it goes
        // through exactly the same gate and handler — so it can never do something the generic
        // invoke would refuse.
        server.registerRoute("/api/media/download") { handleMediaDownload(it) }
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
                endpoint(
                    "/api/media/download", "POST",
                    "Download a URL into the Downloads folder. Returns an id; poll DOWNLOAD_MANAGER.status"
                )
                endpoint(
                    FILE_PULL_ROUTE, "GET",
                    "Stream a file this platform produced. '?capability=CAMERA&id=cap_…' or " +
                        "'?capability=DOWNLOAD_MANAGER&id=dl_…'. Ids only — no path is ever " +
                        "accepted. A capability switched off is refused here exactly as it is on " +
                        "every other route."
                )
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
                            put("group", spec.type.group.name)
                            put("groupName", spec.type.group.displayName)
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

    /**
     * Streams a produced file back to the caller: `GET /api/files/pull?capability=CAMERA&id=…`.
     *
     * Bytes go out here rather than through [invokeGated] because a photo has no useful JSON
     * encoding: base64 costs a third more on the wire and has to exist in memory whole, so pulling
     * a large download would mean building a String several times the size of the file. The action
     * that reports a pull url stays ordinary JSON; this is the one place bytes move.
     *
     * The gate is checked here, first, exactly as it is for an action. Being a raw route buys a
     * different response shape, not an exemption — a capability the user switched off must not be
     * reachable here either, and `a pull for a disabled capability is refused, not streamed` holds
     * that to it.
     *
     * Only an id is ever taken from the request. No path, no filename, nothing a caller could craft
     * to point somewhere else on the device: an id can only name a file this platform wrote.
     */
    internal fun handlePull(request: ServerRequest, out: java.io.OutputStream): Boolean {
        val query = parseQuery(request.rawPath)
        val capabilityId = query["capability"] ?: run {
            refuse(out, 400, """{"error":"Missing 'capability'"}""")
            return true
        }
        val id = query["id"] ?: run {
            refuse(out, 400, """{"error":"Missing 'id'"}""")
            return true
        }

        val type = resolveType(capabilityId)
        if (type == null) {
            refuse(out, 404, """{"error":"No capability named '$capabilityId'"}""")
            return true
        }

        // Same two checks as invokeGated, in the same order, so a file cannot be read through a
        // route an action would have refused.
        if (!gate.isGloballyEnabled()) {
            refuse(out, 403, """{"error":"Capabilities API is switched off"}""")
            return true
        }
        if (!gate.isEnabled(type)) {
            refuse(out, 403, """{"error":"Capability '${type.name}' is switched off"}""")
            return true
        }

        val (fileName, contentType, bytes) = when (type) {
            CapabilityType.CAMERA -> pullCapture(id)
            CapabilityType.DOWNLOAD_MANAGER -> pullDownload(id)
            else -> {
                refuse(
                    out, 404,
                    """{"error":"'${type.name}' does not produce files that can be pulled"}"""
                )
                return true
            }
        } ?: run {
            logger.w("CapabilityApi", "Pull refused: no readable file for $capabilityId id=$id")
            refuse(out, 404, """{"error":"No file for id '$id' in $capabilityId"}""")
            return true
        }

        logger.i("CapabilityApi", "Pulling ${bytes.size} bytes of $fileName for $capabilityId id=$id")
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Content-Disposition: attachment; filename=\"$fileName\"\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
        return true
    }

    /** A camera capture's bytes, or null if there is nothing to send. */
    private fun pullCapture(id: String): Triple<String, String, ByteArray>? {
        val provider = registry.getProvider(CapabilityType.CAMERA)
            as? com.personal.base.platform.capabilities.CameraMicCapabilityProvider ?: return null
        val bytes = provider.readCapture(id) ?: return null
        val name = provider.recentCaptures(Int.MAX_VALUE).firstOrNull { it.id == id }?.fileName
            ?: "$id.jpg"
        return Triple(name, provider.captureContentType(id) ?: "application/octet-stream", bytes)
    }

    /** A finished download's bytes, or null if there is nothing to send. */
    private fun pullDownload(id: String): Triple<String, String, ByteArray>? {
        val provider = registry.getProvider(CapabilityType.DOWNLOAD_MANAGER)
            as? com.personal.base.platform.capabilities.MediaDownloadCapabilityProvider ?: return null
        val download = provider.get(id) ?: return null

        // Read through the content resolver rather than the recorded path: under scoped storage
        // that path is for a person to read, not something the process can open.
        val stream = provider.openDownload(id) ?: return null
        val bytes = stream.use { it.readBytes() }
        return Triple(
            download.fileName,
            download.contentType ?: "application/octet-stream",
            bytes
        )
    }

    /** Writes a JSON refusal as a complete response, since no body is ever streamed after this. */
    private fun refuse(out: java.io.OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = when (status) {
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Bad Request"
        }
        out.write(
            ("HTTP/1.1 $status $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    /** The query string of a request path, parsed. */
    private fun parseQuery(rawPath: String): Map<String, String> {
        val query = rawPath.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            if (name.isEmpty()) null else name to java.net.URLDecoder.decode(value, "UTF-8")
        }.toMap()
    }

    private fun handleList(): ServerResponse =
        ServerResponse.json(json.encodeToString(catalogueSnapshot()))

    /**
     * `POST /api/media/download` — fetch a URL into the Downloads folder.
     *
     * A shortcut for the common case, not a second way in: it resolves the same provider and calls
     * the same handler the capability action uses, so the gate, the scheme check and the error
     * shape are identical whichever route a caller reaches for.
     */
    fun handleMediaDownload(request: ServerRequest): ServerResponse {
        val payload = runCatching { json.parseToJsonElement(request.body) }.getOrNull() as? JsonObject
            ?: return ServerResponse(
                400, "application/json",
                """{"error":"Expected a JSON body","example":{"url":"https://example.com/clip.mp4"}}"""
            )

        return actions.invoke(
            CapabilityType.DOWNLOAD_MANAGER,
            "fetch",
            buildJsonObject {
                payload["url"]?.let { put("url", it) }
                payload["fileName"]?.let { put("fileName", it) }
                payload["mediaType"]?.let { put("mediaType", it) }
            }
        )
    }

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
        // Published so a client can lay the capabilities out by purpose without hardcoding a
        // grouping of its own that would drift from the platform's.
        put("groups", buildJsonArray {
            CapabilityGroup.entries.forEach { group ->
                add(
                    buildJsonObject {
                        put("id", group.name)
                        put("name", group.displayName)
                        put("blurb", group.blurb)
                    }
                )
            }
        })
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
                                statusReason(entry.status)?.let { put("statusReason", it) }
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
        is CapabilityStatus.Partial -> "partial"
    }

    /**
     * Why a status is what it is, for the cases where a bare word tells the user nothing.
     *
     * "partial" on its own is not information. Saying which half works is the difference between a
     * status and an excuse.
     */
    private fun statusReason(status: CapabilityStatus): String? = when (status) {
        is CapabilityStatus.Available -> null
        is CapabilityStatus.Unavailable -> status.reason
        is CapabilityStatus.PermissionRequired -> status.missingPermissions.joinToString("\n")
        is CapabilityStatus.DisabledByUser -> status.policyReason
        is CapabilityStatus.Partial -> "${status.whatWorks} works. ${status.whatDoesNot}"
    }
}
