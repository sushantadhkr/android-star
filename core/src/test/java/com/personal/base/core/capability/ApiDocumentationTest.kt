package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.NotificationActionContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The documentation convention, enforced.
 *
 * Every action the API exposes must be documented. `ActionSpec` already refuses to be constructed
 * without a summary and an example, so this test guards the parts it cannot: that a capability is
 * documented, that no action is undocumented, and that the catalogue and the docs agree.
 */
class ApiDocumentationTest {

    private val actions = CapabilityActions(PlatformCapabilityRegistry(), PlatformLogger())

    @Test
    fun `every exposed action has a summary`() {
        actions.specs.forEach { spec ->
            spec.actions.forEach { action ->
                assertTrue(
                    "${spec.type.name}.${action.name} needs a summary",
                    action.summary.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `every exposed action has a usage example`() {
        actions.specs.forEach { spec ->
            spec.actions.forEach { action ->
                assertTrue(
                    "${spec.type.name}.${action.name} needs an example",
                    action.example.contains("\"action\"")
                )
            }
        }
    }

    @Test
    fun `every documented parameter is explained`() {
        actions.specs.forEach { spec ->
            spec.actions.forEach { action ->
                action.params.forEach { param ->
                    assertTrue(
                        "${spec.type.name}.${action.name}.${param.name} needs a description",
                        param.description.isNotBlank()
                    )
                }
            }
        }
    }

    @Test
    fun `every capability with actions has a summary`() {
        actions.specs.forEach { spec ->
            assertTrue("${spec.type.name} needs a summary", spec.summary.isNotBlank())
        }
    }

    @Test
    fun `action names are unique within a capability`() {
        actions.specs.forEach { spec ->
            val names = spec.actions.map { it.name }
            assertEquals(
                "${spec.type.name} has duplicate action names",
                names.size,
                names.distinct().size
            )
        }
    }

    @Test
    fun `the catalogue advertises exactly the documented actions`() {
        actions.specs.forEach { spec ->
            assertEquals(
                "${spec.type.name} catalogue drifted from its docs",
                spec.names.sorted(),
                actions.actionsFor(spec.type).sorted()
            )
        }
    }

    @Test
    fun `capabilities with no implementation advertise nothing`() {
        // Advertising an action a provider cannot perform would be a lie. These have no provider at
        // all, or a provider with nothing to call, so they must offer nothing.
        listOf(
            // The service class exists for each of these but is not registered by the shell, so
            // nothing can reach it. Advertising an action would be a 409 forever.
            "NOTIFICATION_LISTENER",
            "DOCUMENTS_PROVIDER",
            // A provider that only sets a flag and logs "daemon initialized" opens no socket.
            "SFTP_SERVER"
        ).forEach { id ->
            val type = CapabilityType.entries.first { it.name == id }
            assertTrue(
                "$id has no implementation and must advertise no actions",
                actions.actionsFor(type).isEmpty()
            )
        }
    }

    @Test
    fun `every advertised action names a provider the container builds`() {
        // An action pointing at a provider the shell never constructs answers 409 forever, which
        // is a promise the platform cannot keep. The real container is read rather than a list
        // kept beside the code, because a list kept beside the code is what goes stale.
        val container = File("../app/src/main/java/com/personal/base/di/PlatformContainer.kt")
        assertTrue("the container should be readable from here", container.exists())
        val source = container.readText()

        // Construction rather than `register(...)` specifically, because a provider may be built
        // into a variable and registered by that name — which is the normal pattern once a provider
        // has dependencies.
        val built = Regex("""\b([A-Za-z]+)\(""").findAll(source)
            .map { it.groupValues[1] }
            .filter { it.endsWith("CapabilityProvider") }
            .toSet()

        val actionsFile = File("src/main/java/com/personal/base/core/capability/CapabilityActions.kt")
        assertTrue(
            "the actions file should be readable from here: " + actionsFile.absolutePath,
            actionsFile.exists()
        )
        val advertised = Regex("provider<([A-Za-z]+)>")
            .findAll(actionsFile.readText())
            .map { it.groupValues[1] }
            .toSet()

        val orphans = advertised - built
        assertTrue(
            "these providers back advertised actions but the container never builds them: $orphans",
            orphans.isEmpty()
        )
        assertTrue("the test found only ${advertised.size} providers: $advertised", advertised.size > 20)
    }

    @Test
    fun `a provider that opens no socket is not registered`() {
        // SftpServerCapabilityProvider sets a boolean and logs "SFTP daemon initialized" without
        // opening anything. Registering it would tell a caller a server is running when there is
        // nothing to connect to, which is the one thing this API must never do.
        val container = File("../app/src/main/java/com/personal/base/di/PlatformContainer.kt")
        assertTrue(
            "SFTP has no daemon behind it and must stay unregistered",
            !Regex("""\bSftpServerCapabilityProvider\(""").findAll(container.readText()).any()
        )
        assertTrue(
            "SFTP_SERVER must advertise nothing while it does nothing",
            actions.actionsFor(CapabilityType.SFTP_SERVER).isEmpty()
        )
    }

    @Test
    fun `actions needing user consent say so`() {
        val mic = actions.specFor(CapabilityType.MICROPHONE)!!.action("record")!!
        assertTrue("microphone recording must declare consent", mic.requiresUserConsent)

        val haptic = actions.specFor(CapabilityType.DEVICE_ACTIONS)!!.action("haptic")!!
        assertTrue("haptics need no consent", !haptic.requiresUserConsent)
    }

    @Test
    fun `docs are served for every documented capability`() {
        assertTrue("no capabilities documented", actions.specs.isNotEmpty())
    }

    @Test
    fun `posting a notification documents the link parameter`() {
        val post = actions.specFor(CapabilityType.NOTIFICATIONS)!!.action("post")!!
        assertTrue(
            "the link option must be documented",
            post.params.any { it.name == "link" }
        )
        assertTrue(
            "the example must show a link being used",
            post.example.contains("\"link\"")
        )
    }

    @Test
    fun `the link parameter is optional so a plain notification still works`() {
        val post = actions.specFor(CapabilityType.NOTIFICATIONS)!!.action("post")!!
        val link = post.params.first { it.name == "link" }
        assertTrue("link must be optional", !link.required)
    }

    @Test
    fun `posting a notification documents its buttons`() {
        val post = actions.specFor(CapabilityType.NOTIFICATIONS)!!.action("post")!!
        val buttons = post.params.firstOrNull { it.name == "buttons" }

        assertTrue("the buttons option must be documented", buttons != null)
        assertTrue("buttons must be optional", !buttons!!.required)

        val described = buttons.description
        listOf("id", "label", "icon", "input", "invoke").forEach { field ->
            assertTrue("buttons docs must mention '$field'", described.contains(field))
        }
        NotificationActionContract.ICONS.forEach { icon ->
            assertTrue("buttons docs must list the '$icon' icon", described.contains(icon))
        }
        assertTrue(
            "buttons docs must show where typed text goes",
            described.contains("CAPABILITY:action:param")
        )
    }

    @Test
    fun `the example shows a real button rather than an empty array`() {
        val post = actions.specFor(CapabilityType.NOTIFICATIONS)!!.action("post")!!
        assertTrue(post.example.contains("\"buttons\":["))
        assertTrue(post.example.contains("\"invoke\""))
    }

    @Test
    fun `button presses can be read back`() {
        val log = actions.specFor(CapabilityType.NOTIFICATIONS)!!.action("buttonLog")
        assertTrue("a press that cannot be read back is a side effect", log != null)
        assertTrue(log!!.example.contains("\"action\""))
    }
}
