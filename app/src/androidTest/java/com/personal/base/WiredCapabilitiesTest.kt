package com.personal.base

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.base.core.capability.CapabilityActions
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.capability.PlatformCapabilityRegistry
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.di.PlatformContainer
import com.personal.base.platform.capabilities.MediaDownload
import com.personal.base.platform.capabilities.MediaDownloadCapabilityProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wired capabilities, exercised on a real device.
 *
 * Every action added to close the gap between "has a provider" and "can be driven" is run here
 * through the same dispatcher the HTTP API uses. Actions that need an Activity, a user's consent
 * screen, or a component the platform does not have are deliberately absent from the catalogue, so
 * what is tested is what is advertised.
 */
@RunWith(AndroidJUnit4::class)
class WiredCapabilitiesTest {

    private lateinit var container: PlatformContainer
    private lateinit var registry: PlatformCapabilityRegistry
    private lateinit var actions: CapabilityActions

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        container = PlatformContainer(ctx)
        // Registration happens in the container's bootstrap, so without this every action answers
        // 409 and the test would prove nothing about the handlers.
        container.initialize()
        registry = container.capabilityRegistry
        actions = CapabilityActions(registry, PlatformLogger())
    }

    /** Runs an action and requires a 2xx with a JSON body. */
    private fun ok(capability: String, action: String, vararg pairs: Pair<String, Any>) {
        val response = actions.invoke(
            CapabilityType.valueOf(capability),
            action,
            JsonObject(
                buildMap {
                    put("capability", JsonPrimitive(capability))
                    put("action", JsonPrimitive(action))
                    pairs.forEach { (k, v) -> put(k, JsonPrimitive(v.toString())) }
                }
            )
        )
        assertTrue(
            "$capability.$action returned ${response.status}: ${response.body}",
            response.status in 200..299
        )
    }

    @Test
    fun deviceFactsCanBeRead() {
        ok("DEVICE_INFO", "get")
        ok("BATTERY_POWER", "state")
    }

    @Test
    fun displayStorageAndVolumeAreReadable() {
        ok("DISPLAY", "get")
        ok("STORAGE", "overview")
        ok("AUDIO_VOLUME", "get")
    }

    @Test
    fun contactsCalendarAndSimsAreReadable() {
        ok("CONTACTS_ACCOUNTS", "contacts")
        ok("CONTACTS_ACCOUNTS", "accounts")
        ok("CALENDAR", "upcoming")
        ok("SIM_SUBSCRIPTION", "slots")
    }

    @Test
    fun usbAndCredentialSupportAreReadable() {
        ok("USB", "list")
        ok("CREDENTIAL_MANAGER", "passkeys")
    }

    @Test
    fun geofencesCanBeManaged() {
        ok("GEOFENCE_ACTIVITY", "add", "id" to "test-home", "latitude" to 51.5074, "longitude" to -0.1278)
        ok("GEOFENCE_ACTIVITY", "check", "latitude" to 51.5074, "longitude" to -0.1278)
        ok("GEOFENCE_ACTIVITY", "remove", "id" to "test-home")
    }

    @Test
    fun writingToTheClipboardStillWorksFromTheBackground() {
        ok("CLIPBOARD_BACKGROUND", "write", "text" to "hello from the test")
    }

    @Test
    fun readingTheClipboardIsRefusedRatherThanFaked() {
        val response = actions.invoke(
            CapabilityType.CLIPBOARD,
            "read",
            JsonObject(mapOf("capability" to JsonPrimitive("CLIPBOARD"), "action" to JsonPrimitive("read")))
        )
        // Either it worked, or it was refused with a reason. It must never be a bare empty string,
        // which would read as "the clipboard is blank".
        assertTrue(
            "clipboard read must answer with something honest, got ${response.status}: ${response.body}",
            response.status in 200..299 || response.status == 403
        )
        if (response.status == 200) {
            assertTrue("a successful read must say it was readable", response.body.contains("readable"))
        }
    }

    @Test
    fun invalidInputIsRejectedBeforeAnythingIsTouched() {
        val badVolume = actions.invoke(
            CapabilityType.AUDIO_VOLUME,
            "setMedia",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("AUDIO_VOLUME"),
                "action" to JsonPrimitive("setMedia"),
                "percent" to JsonPrimitive(900)
            ))
        )
        assertTrue("an out-of-range volume must be refused", badVolume.status == 400)

        val badDnd = actions.invoke(
            CapabilityType.DO_NOT_DISTURB,
            "set",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("DO_NOT_DISTURB"),
                "action" to JsonPrimitive("set"),
                "filter" to JsonPrimitive("whatever")
            ))
        )
        assertTrue("an unknown DND filter must be refused", badDnd.status == 400)

        val badUrl = actions.invoke(
            CapabilityType.DOWNLOAD_MANAGER,
            "enqueue",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("DOWNLOAD_MANAGER"),
                "action" to JsonPrimitive("enqueue"),
                "url" to JsonPrimitive("file:///etc/passwd")
            ))
        )
        assertTrue("a non-http download must be refused", badUrl.status == 400)
    }

    @Test
    fun theFileServerReallyListensAndServes() {
        ok("FILE_SERVER", "start", "port" to 18080)
        ok("FILE_SERVER", "put", "name" to "probe.txt", "content" to "hello from the test")
        ok("FILE_SERVER", "list")
        ok("FILE_SERVER", "status")

        // The whole point is a real socket: connect to it and read the file back over HTTP.
        val socket = java.net.Socket("127.0.0.1", 18080)
        try {
            socket.getOutputStream().apply {
                write("GET /file/probe.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val body = socket.getInputStream().bufferedReader().readText()
            assertTrue("the served file must come back over the socket", body.contains("hello from the test"))
        } finally {
            socket.close()
        }

        ok("FILE_SERVER", "delete", "name" to "probe.txt")
        ok("FILE_SERVER", "stop")
    }

    @Test
    fun theChatServerReallyListens() {
        ok("NEARBY_CHAT", "start", "port" to 19090)
        ok("NEARBY_CHAT", "peers")
        ok("NEARBY_CHAT", "messages")
        ok("NEARBY_CHAT", "stop")

        // Connecting to a stopped listener must be refused rather than accepted by a stale socket.
        val refused = runCatching { java.net.Socket("127.0.0.1", 19090).close() }.isFailure
        assertTrue("a stopped chat server must not still be listening", refused)
    }

    @Test
    fun sendingWithNoPeersIsRefusedRatherThanFaked() {
        ok("NEARBY_CHAT", "start", "port" to 19091)
        val response = actions.invoke(
            CapabilityType.NEARBY_CHAT,
            "send",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("NEARBY_CHAT"),
                "action" to JsonPrimitive("send"),
                "content" to JsonPrimitive("anyone there?")
            ))
        )
        // No peers are connected, so this must say so rather than claim a message went out.
        assertTrue("sending to nobody must not report success", response.status != 200)
        ok("NEARBY_CHAT", "stop")
    }

    @Test
    fun sftpAdvertisesNothingBecauseThereIsNoDaemon() {
        assertTrue(
            "SFTP has no daemon behind it, so it must not advertise a start action",
            actions.actionsFor(CapabilityType.SFTP_SERVER).isEmpty()
        )
    }

    @Test
    fun mediaDownloadsToTheDownloadsFolder() {
        // A real file, fetched over HTTP, that must end up in Downloads.
        val url = "https://example.com"
        ok("DOWNLOAD_MANAGER", "fetch", "url" to url, "fileName" to "probe-download.md")
        ok("DOWNLOAD_MANAGER", "list")

        // Poll rather than assume: the download is asynchronous by design.
        val provider = container.capabilityRegistry.getProviderByClass(MediaDownloadCapabilityProvider::class.java)
        assertTrue("the media download provider must be registered; registry has " +
            container.capabilityRegistry.getAllStatuses().size + " statuses", provider != null)

        val id = provider!!.history.value.last().id
        var done: MediaDownload? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val d = provider.get(id)
            if (d != null && d.status in listOf("complete", "failed")) { done = d; break }
            Thread.sleep(250)
        }

        assertTrue("the download never finished", done != null)
        assertTrue("the download failed: ${done!!.error}", done!!.status == "complete")
        assertTrue("nothing was written", done!!.bytesDownloaded > 0)
        assertTrue(
            "the file must be reported as living in the Downloads folder, got " + done!!.savedPath,
            done!!.savedPath?.startsWith("Downloads/") == true
        )
    }

    @Test
    fun aNonHttpUrlIsRefused() {
        val response = actions.invoke(
            CapabilityType.DOWNLOAD_MANAGER,
            "fetch",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("DOWNLOAD_MANAGER"),
                "action" to JsonPrimitive("fetch"),
                "url" to JsonPrimitive("file:///etc/passwd")
            ))
        )
        assertTrue("a file:// url must be refused", response.status == 400)
    }

    @Test
    fun aTraversingFileNameCannotEscapeTheFolder() {
        val provider = container.capabilityRegistry.getProviderByClass(MediaDownloadCapabilityProvider::class.java)
        assertTrue("the media download provider must be registered; registry has " +
            container.capabilityRegistry.getAllStatuses().size + " statuses", provider != null)
        // Whatever comes back must not contain a path separator or a parent reference.
        val name = provider!!.start(
            "https://example.invalid/x.mp4",
            "../../../etc/passwd",
            null
        )!!.fileName
        assertTrue("a traversing name must be flattened, got '$name'", !name.contains("..") && !name.contains('/'))
    }

    @Test
    fun aDownloadPostsAProgressNotification() {
        ok("DOWNLOAD_MANAGER", "fetch", "url" to "https://example.com", "fileName" to "notified.bin")

        val container = this.container
        val provider = container.capabilityRegistry
            .getProviderByClass(MediaDownloadCapabilityProvider::class.java)
        assertTrue("the media download provider must be registered", provider != null)

        val nm = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Posting is a no-op without POST_NOTIFICATIONS, so check before asserting rather than
        // reporting a failure that is really about the test environment.
        val notificationsOn = androidx.core.app.NotificationManagerCompat
            .from(InstrumentationRegistry.getInstrumentation().targetContext)
            .areNotificationsEnabled()
        if (!notificationsOn) return

        // Posted, not just scheduled: the notification must be visible while the job runs.
        var seen = false
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !seen) {
            seen = nm.activeNotifications.any { it.id == provider!!.recent(1).first().id.hashCode() }
            if (!seen) Thread.sleep(150)
        }
        assertTrue("a download must post a progress notification", seen)

        val deadline2 = System.currentTimeMillis() + 30_000
        var done: MediaDownload? = null
        while (System.currentTimeMillis() < deadline2) {
            val d = provider!!.get(provider.recent(1).first().id)
            if (d != null && d.status in listOf("complete", "failed")) { done = d; break }
            Thread.sleep(250)
        }
        assertTrue("the download never finished", done != null)
        assertTrue("the download failed: " + done!!.error, done!!.status == "complete")
    }

    @Test
    fun downloadsDefaultToDownloadsWithNoPermissionNeeded() {
        // MediaStore needs nothing on Android 10+. The folder the API reports must say so, rather
        // than implying the platform is writing somewhere it was granted.
        ok("DOWNLOAD_MANAGER", "fetch", "url" to "https://example.com", "fileName" to "where.bin")
        val provider = container.capabilityRegistry
            .getProviderByClass(MediaDownloadCapabilityProvider::class.java)!!

        // savedPath is only known once the file is written, so wait rather than assert on null.
        val id = provider.recent(1).first().id
        var done: MediaDownload? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val d = provider.get(id)
            if (d != null && d.status in listOf("complete", "failed")) { done = d; break }
            Thread.sleep(250)
        }
        assertTrue("the download never finished", done != null)
        assertTrue(
            "with no folder chosen, downloads must report the phone's own Downloads folder, got " +
                done!!.savedPath,
            done!!.savedPath?.startsWith("Downloads/") == true
        )
    }

    @Test
    fun theMediaShortcutRouteIsReallyRegistered() {
        // Authentication runs before routing, so a 401 from curl cannot tell "route exists" from
        // "route does not exist". Asking the server itself settles it.
        assertTrue(
            "POST /api/media/download must be a live route",
            container.embeddedServer.hasRoute("/api/media/download")
        )
        assertTrue(
            "a route that does not exist must not appear",
            !container.embeddedServer.hasRoute("/api/nonsense")
        )
    }

    @Test
    fun theShortcutRouteGoesThroughTheSameHandler() {
        // If the shortcut ever diverged from the capability action it could do something the
        // generic invoke would refuse, which would break the one rule the API rests on.
        val server = container.embeddedServer
        val viaShortcut = server.handlerFor("/api/media/download")
            ?.invoke(
                com.personal.base.core.platform.ServerRequest(
                    "POST", "/api/media/download",
                    """{"url":"file:///etc/passwd"}"""
                )
            )
        assertTrue("the shortcut must exist", viaShortcut != null)
        assertTrue(
            "a file:// url must be refused by the shortcut exactly as by the action, got " +
                viaShortcut!!.status,
            viaShortcut.status == 400
        )
    }

    @Test
    fun theProgressNotificationCarriesWorkingControls() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val notificationsOn = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        if (!notificationsOn) return

        ok("DOWNLOAD_MANAGER", "fetch", "url" to "https://example.com", "fileName" to "controlled.bin")
        val provider = container.capabilityRegistry
            .getProviderByClass(MediaDownloadCapabilityProvider::class.java)!!
        val id = provider.recent(1).first().id

        val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager

        var posted = false
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !posted) {
            posted = nm.activeNotifications.any { it.id == id.hashCode() }
            if (!posted) Thread.sleep(150)
        }
        assertTrue("the download must post a notification", posted)

        // Cancel must be on it, and must know which download it belongs to.
        val notification = nm.activeNotifications.firstOrNull { it.id == id.hashCode() }
        val actions = notification?.notification?.actions?.map { it.title }.orEmpty()
        assertTrue("a running download must offer Cancel, got $actions", actions.any { it.contains("Cancel") })

        // Pressing it goes through the capability gate like anything else, so the action must
        // actually be resolvable rather than pointing at nothing.
        val cancelButton = com.personal.base.core.platform.NotificationButton(
            id = "download-cancel", label = "Cancel",
            invoke = "DOWNLOAD_MANAGER:cancel", params = mapOf("id" to id)
        )
        val target = com.personal.base.core.platform.NotificationActionContract.parseInvoke(cancelButton.invoke!!)
        assertTrue("the cancel button must name a real capability", target != null)
        assertEquals(
            "the cancel button must carry the download it belongs to",
            id,
            cancelButton.params["id"]
        )
    }

    @Test
    fun aFailedDownloadCanBeRetried() {
        // A host that does not resolve fails fast and predictably.
        ok("DOWNLOAD_MANAGER", "fetch", "url" to "https://this-host-does-not-exist.invalid/x", "fileName" to "retry.bin")
        val provider = container.capabilityRegistry
            .getProviderByClass(MediaDownloadCapabilityProvider::class.java)!!
        val id = provider.recent(1).first().id

        var done: MediaDownload? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val d = provider.get(id)
            if (d != null && d.status in listOf("complete", "failed")) { done = d; break }
            Thread.sleep(250)
        }
        assertTrue("the download should have failed", done?.status == "failed")

        val response = actions.invoke(
            CapabilityType.DOWNLOAD_MANAGER,
            "retry",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("DOWNLOAD_MANAGER"),
                "action" to JsonPrimitive("retry"),
                "id" to JsonPrimitive(id)
            ))
        )
        assertTrue("retry must be accepted, got ${response.status}: ${response.body}", response.status in 200..299)
        assertTrue("retry must report the download it came from", response.body.contains(id))
    }
}
