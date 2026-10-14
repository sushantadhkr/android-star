package com.personal.base.core.capability

import com.personal.base.core.platform.EmbeddedServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exposes the platform's capabilities over the embedded server.
 *
 * This is the pinned Platform surface: one endpoint set that describes every capability the
 * platform knows about, including those that are currently unavailable. A capability is reported
 * with its status rather than omitted, so a client can always discover the full surface.
 *
 * Capabilities that are switched off are reported as such — never silently dropped.
 */
object CapabilityApi {

    private val json = Json { prettyPrint = false }

    /** Registers the capability routes on the platform's embedded server. */
    fun register(server: EmbeddedServer, catalogue: CapabilityCatalogue) {
        catalogue.refresh()

        server.registerRoute("/api/capabilities") { _ ->
            json.encodeToString(catalogueSnapshot(catalogue))
        }

        server.registerRoute("/api/capabilities/status") { _ ->
            json.encodeToString(catalogueSnapshot(catalogue))
        }
    }

    private fun catalogueSnapshot(catalogue: CapabilityCatalogue): JsonObject = buildJsonObject {
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