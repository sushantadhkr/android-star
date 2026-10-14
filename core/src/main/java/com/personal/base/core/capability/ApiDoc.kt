package com.personal.base.core.capability

import com.personal.base.core.platform.ServerResponse
import kotlinx.serialization.json.JsonObject

/**
 * A documented request field.
 */
data class ParamDoc(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
    val example: String? = null
)

/**
 * An action the API exposes, together with its documentation and its implementation.
 *
 * Documentation lives *inside* the action definition rather than beside it. That is the whole point:
 * the catalogue, the docs endpoint and the dispatcher all read this one object, so a new action
 * cannot be added without also being documented, and its docs cannot drift from what it does.
 */
data class ActionSpec(
    val name: String,
    val summary: String,
    val params: List<ParamDoc> = emptyList(),
    val example: String = "",
    val returns: String = "",
    val requiresUserConsent: Boolean = false,
    val handler: (JsonObject) -> ServerResponse
) {
    init {
        // Fail loudly at construction rather than shipping an undocumented endpoint.
        require(summary.isNotBlank()) { "Action '$name' must have a summary" }
        require(example.isNotBlank()) { "Action '$name' must have a usage example" }
    }
}

/**
 * Everything the API exposes for one capability.
 */
data class CapabilitySpec(
    val type: CapabilityType,
    val summary: String,
    val actions: List<ActionSpec>
) {
    val names: List<String> get() = actions.map { it.name }
    fun action(name: String): ActionSpec? =
        actions.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
