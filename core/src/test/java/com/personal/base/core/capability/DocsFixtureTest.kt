package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the fixture the HTML console is checked against in step with the real API.
 *
 * `index.html` builds its whole interface out of `/api/capabilities/docs`, so if the shape of that
 * payload ever changes the console can silently stop working. `tools/check-console.js` runs against
 * this file, so regenerating it here is what keeps that check honest.
 */
class DocsFixtureTest {

    @Test
    fun `the docs payload the console is checked against is current`() {
        val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())

        val payload = buildJsonObject {
            put("title", "Android Star — Capabilities API")
            put("authentication", "Send 'Authorization: Bearer <token>'.")
            put("appRoutes", buildJsonArray { })
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
                            put("requiredPermissions", buildJsonArray {
                                spec.type.requiredPermissions.forEach {
                                    add(kotlinx.serialization.json.JsonPrimitive(it))
                                }
                            })
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

        // The console pre-fills forms by parsing every example, so a non-JSON example is a real
        // break, not a cosmetic one.
        actions.specs.forEach { spec ->
            spec.actions.forEach { action ->
                runCatching { Json.parseToJsonElement(action.example) }
                    .onFailure { throw AssertionError("${spec.type.name}.${action.name} example is not JSON", it) }
            }
        }

        val file = File(FIXTURE_PATH).apply { parentFile.mkdirs() }
        file.writeText(payload)

        assertTrue("the docs payload must not be empty", payload.length > 1000)
    }

    private companion object {
        // Tests run with the module directory as their working directory, so the
        // repository's shared fixture lives one level up.
        const val FIXTURE_PATH = "../tools/fixtures/docs-sample.json"
    }
}