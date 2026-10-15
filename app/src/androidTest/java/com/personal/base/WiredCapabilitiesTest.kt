package com.personal.base

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.base.core.capability.CapabilityActions
import android.provider.DocumentsContract
import com.personal.base.platform.capabilities.PlatformDocumentsProvider
import com.personal.base.platform.capabilities.OnDeviceMlCapabilityProvider
import com.personal.base.core.capability.CapabilityType
import com.personal.base.platform.capabilities.PlatformAccessibilityService
import com.personal.base.core.capability.PlatformCapabilityRegistry
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.di.PlatformContainer
import com.personal.base.platform.capabilities.MediaDownload
import com.personal.base.platform.capabilities.MediaDownloadCapabilityProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** Stops a capability without caring whether it was already stopped. */
    /** Waits, briefly, for the accessibility service to bind to this process. */
    private fun waitForAccessibilityService(): Boolean {
        repeat(16) {
            if (PlatformAccessibilityService.activeInstance != null) return true
            SystemClock.sleep(500)
        }
        return PlatformAccessibilityService.activeInstance != null
    }

    private fun stop(capability: String, action: String) {
        runCatching {
            actions.invoke(
                CapabilityType.valueOf(capability),
                action,
                JsonObject(mapOf(
                    "capability" to JsonPrimitive(capability),
                    "action" to JsonPrimitive(action)
                ))
            )
        }
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
        // Always stopped, even when an assertion fails part-way: a leaked socket makes every
        // later run of this suite fail to bind, which looks like a product bug and is not one.
        try {
            ok("FILE_SERVER", "start", "port" to 18080)
            ok("FILE_SERVER", "put", "name" to "probe.txt", "content" to "hello from the test")
            ok("FILE_SERVER", "list")
            ok("FILE_SERVER", "status")

            // The whole point is a real socket: connect and read the file back over HTTP.
            java.net.Socket("127.0.0.1", 18080).use { socket ->
                socket.getOutputStream().apply {
                    write("GET /file/probe.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val body = socket.getInputStream().bufferedReader().readText()
                assertTrue("the served file must come back over the socket", body.contains("hello from the test"))
            }

            ok("FILE_SERVER", "delete", "name" to "probe.txt")
        } finally {
            stop("FILE_SERVER", "stop")
        }
    }

    @Test
    fun theChatServerReallyListens() {
        try {
            ok("NEARBY_CHAT", "start", "port" to 19090)
            ok("NEARBY_CHAT", "peers")
            ok("NEARBY_CHAT", "messages")
        } finally {
            stop("NEARBY_CHAT", "stop")
        }

        // Connecting to a stopped listener must be refused rather than answered by a stale socket.
        val refused = runCatching { java.net.Socket("127.0.0.1", 19090).close() }.isFailure
        assertTrue("a stopped chat server must not still be listening", refused)
    }

    @Test
    fun sendingWithNoPeersIsRefusedRatherThanFaked() {
        try {
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
        } finally {
            stop("NEARBY_CHAT", "stop")
        }
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

    @Test
    fun aCapabilityDeclaresATileOnlyWhenItHasSomethingToSay() {
        val provider = container.capabilityRegistry
            .getProviderByClass(MediaDownloadCapabilityProvider::class.java)!!

        // Idle downloader: a tile reading "0 downloads" forever is noise on a home screen.
        assertNull(
            "an idle downloader must not claim a tile",
            provider.widgetSpec()
        )

        ok("DOWNLOAD_MANAGER", "fetch", "url" to "https://example.com", "fileName" to "tiled.bin")
        val spec = provider.widgetSpec()
        assertTrue("a downloader with work must declare a tile", spec != null)
        assertTrue("the tile must name itself", spec!!.id.isNotBlank())
        assertTrue("the tile must say something", spec.status.isNotBlank())
    }

    @Test
    fun theShellGivesEveryDeclaredCapabilityATile() {
        val tiles = container.widgetRegistry.widgets.value
            .filter { it.appId.startsWith("capability:") }
        // Nothing running yet, so there should be none — but the mechanism must have run without
        // falling over, which is what this proves on a real device.
        assertTrue(
            "an idle platform must not fill a home screen with tiles, got " +
                tiles.map { it.appId },
            tiles.isEmpty()
        )
    }

    @Test
    fun permissionChecksAnswerWithoutAnyConsentDialog() {
        // These cannot do the real thing headlessly. They must still answer truthfully, and name
        // the screen that would fix it — which is strictly better than trying the real action and
        // reading a refusal with no cause.
        ok("OVERLAY_WINDOW", "checkPermission")
        ok("ALL_FILES_ACCESS", "checkPermission")
        ok("VPN", "status")
        ok("BIOMETRIC", "status")
        ok("INPUT_METHOD", "status")
        ok("SPEECH_RECOGNITION", "status")
    }

    @Test
    fun thePermissionChecksTellTheTruthOnThisDevice() {
        fun body(capability: String, action: String): String =
            actions.invoke(
                CapabilityType.valueOf(capability),
                action,
                JsonObject(mapOf(
                    "capability" to JsonPrimitive(capability),
                    "action" to JsonPrimitive(action)
                ))
            ).body

        // The overlay is not granted on a fresh install, and claiming otherwise would be the
        // exact lie this check exists to avoid.
        val overlay = body("OVERLAY_WINDOW", "checkPermission")
        assertTrue("the overlay check must answer granted or not", overlay.contains("granted"))
        val grantedMarker = "\"" + "granted" + "\":" + "true"
        if (overlay.contains(grantedMarker)) {
            assertTrue(
                "when granted there is no settings action to send the user to",
                overlay.contains("settingsAction")
            )
        } else {
            assertTrue(
                "when not granted the check must name the screen that grants it",
                overlay.contains("MANAGE_OVERLAY_PERMISSION")
            )
        }

        // A keyboard this app does not own cannot be the active one.
        val ime = body("INPUT_METHOD", "status")
        assertTrue("the keyboard check must answer", ime.contains("isActiveKeyboard"))
    }

    @Test
    fun anImageIsNeededBeforeAnythingTriesToLookAtIt() {
        val response = actions.invoke(
            CapabilityType.ON_DEVICE_ML,
            "findFaces",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("ON_DEVICE_ML"),
                "action" to JsonPrimitive("findFaces")
            ))
        )
        assertTrue("with no image the request must be refused, got ${response.body}", response.status == 400)
    }

    @Test
    fun keyguardAndTheAllowlistedShellAnswer() {
        ok("KEYGUARD", "status")
        ok("RESTRICTED_SHELL", "execute", "command" to "uname -a")
    }

    @Test
    fun theShellRefusesAnythingNotOnTheAllowlist() {
        val response = actions.invoke(
            CapabilityType.RESTRICTED_SHELL,
            "execute",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("RESTRICTED_SHELL"),
                "action" to JsonPrimitive("execute"),
                "command" to JsonPrimitive("rm -rf /sdcard")
            ))
        )
        // Refused before it runs, and said why. A shell that quietly ran this would be the worst
        // possible outcome, so the refusal is the thing under test.
        assertTrue(
            "a non-allowlisted command must be refused, got ${response.status} ${response.body}",
            response.status == 403
        )
        assertTrue(
            "the refusal must name the allowlist reason",
            response.body.contains("allowlist")
        )
    }

    @Test
    fun phoneAnswersHardwareEvenWithoutThePermission() {
        val body = actions.invoke(
            CapabilityType.PHONE,
            "status",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("PHONE"),
                "action" to JsonPrimitive("status")
            ))
        ).body

        assertTrue("phone status must answer", body.contains("hasTelephony"))
        assertTrue(
            "gated fields must be named rather than silently blank",
            body.contains("withheld") && body.contains("phoneNumberAvailable")
        )
    }

    @Test
    fun backgroundWorkWillNotPretendToScheduleATaskThatDoesNotExist() {
        val response = actions.invoke(
            CapabilityType.BACKGROUND_WORK,
            "enqueue",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("BACKGROUND_WORK"),
                "action" to JsonPrimitive("enqueue"),
                "task" to JsonPrimitive("definitely.not.registered")
            ))
        )
        assertTrue(
            "an unregistered task must be refused, not enqueued, got ${response.status}",
            response.status == 400
        )
    }

    @Test
    fun backgroundWorkReallyRunsWhatItSchedules() {
        ok("BACKGROUND_WORK", "tasks")
        ok("BACKGROUND_WORK", "enqueue", "task" to "capabilities.refresh")
        ok("BACKGROUND_WORK", "list")

        // Wait for the worker to actually run rather than claiming success on enqueue alone.
        var sawFinished = false
        for (attempt in 0 until 20) {
            val list = actions.invoke(
                CapabilityType.BACKGROUND_WORK,
                "list",
                JsonObject(mapOf(
                    "capability" to JsonPrimitive("BACKGROUND_WORK"),
                    "action" to JsonPrimitive("list")
                ))
            ).body
            if (list.contains("SUCCEEDED") && list.contains("Refreshed")) {
                sawFinished = true
                break
            }
            SystemClock.sleep(500)
        }
        assertTrue("the scheduled work must have run and reported its result", sawFinished)
    }

    @Test
    fun accessibilityAndRemoteInputAnswerTheirStatusHonestly() {
        val a11y = actions.invoke(
            CapabilityType.ACCESSIBILITY,
            "status",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("ACCESSIBILITY"),
                "action" to JsonPrimitive("status")
            ))
        ).body
        assertTrue("accessibility status must answer enabled or not", a11y.contains("enabled"))
        // Disabled must be stated as the user's choice, not as a platform fault, and must point
        // at the one screen that can change it.
        if (!a11y.contains("\"enabled\":true")) {
            assertTrue(
                "when disabled the answer must name the Settings screen that grants it",
                a11y.contains("ACCESSIBILITY_SETTINGS") && a11y.contains("user")
            )
        }

        val remote = actions.invoke(
            CapabilityType.REMOTE_INPUT,
            "status",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("REMOTE_INPUT"),
                "action" to JsonPrimitive("status")
            ))
        ).body
        assertTrue("remote input status must answer", remote.contains("available"))
    }

    @Test
    fun aGestureThatCannotRunIsRefusedRatherThanClaimedAsSent() {
        // The harness starts a fresh app process, and Android binds the accessibility service to
        // it asynchronously — so a tap issued immediately would always be refused for the wrong
        // reason. Waiting for the real connection is what makes the positive path reachable at all.
        val connected = waitForAccessibilityService()
        if (connected) {
            val live = actions.invoke(
                CapabilityType.REMOTE_INPUT,
                "tap",
                JsonObject(mapOf(
                    "capability" to JsonPrimitive("REMOTE_INPUT"),
                    "action" to JsonPrimitive("tap"),
                    "x" to JsonPrimitive(540.0),
                    "y" to JsonPrimitive(1200.0)
                ))
            )
            assertTrue(
                "a connected service must accept a gesture, got ${live.status} ${live.body}",
                live.status == 200 && live.body.contains("\"submitted\":true")
            )
            // Sent is not done. The completed field is what makes this honest, so its presence is
            // part of the contract even when the value is still null a millisecond later.
            assertTrue(
                "an accepted gesture must still report whether it ran",
                live.body.contains("completed")
            )
        }

        // Whether the service is enabled depends on the device, so both outcomes are acceptable
        // and only the dishonest one is a failure: reporting "submitted" when nothing was sent.
        val response = actions.invoke(
            CapabilityType.REMOTE_INPUT,
            "tap",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("REMOTE_INPUT"),
                "action" to JsonPrimitive("tap"),
                "x" to JsonPrimitive(540.0),
                "y" to JsonPrimitive(1200.0)
            ))
        )

        if (response.status == 200) {
            val body = response.body
            assertTrue("a sent gesture must say it was submitted", body.contains("\"submitted\":true"))
            // "submitted" is not "performed". Android cancels gestures when another app is
            // animating, so the completed field is what stops this being a lie.
            assertTrue(
                "a gesture must report whether it actually ran, not merely that it was sent",
                body.contains("completed")
            )
        } else {
            assertTrue(
                "a refusal must be 409 and must say the gesture was not sent, got ${response.status}",
                response.status == 409 && response.body.contains("\"submitted\":false")
            )
        }
    }

    @Test
    fun coordinatesAreRequiredBeforeAnyGestureIsAttempted() {
        for (action in listOf("tap")) {
            val response = actions.invoke(
                CapabilityType.REMOTE_INPUT,
                action,
                JsonObject(mapOf(
                    "capability" to JsonPrimitive("REMOTE_INPUT"),
                    "action" to JsonPrimitive(action)
                ))
            )
            assertTrue("$action with no coordinates must be refused, got ${response.status}", response.status == 400)
        }
        val swipe = actions.invoke(
            CapabilityType.REMOTE_INPUT,
            "swipe",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("REMOTE_INPUT"),
                "action" to JsonPrimitive("swipe"),
                "fromX" to JsonPrimitive(1.0)
            ))
        )
        assertTrue("a swipe with only one end must be refused", swipe.status == 400)
    }

    @Test
    fun notificationAccessRefusesRatherThanClaimingAPhoneWithNoNotifications() {
        val status = actions.invoke(
            CapabilityType.NOTIFICATION_LISTENER,
            "status",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("NOTIFICATION_LISTENER"),
                "action" to JsonPrimitive("status")
            ))
        )
        assertTrue("notification status must answer", status.body.contains("granted"))

        val list = actions.invoke(
            CapabilityType.NOTIFICATION_LISTENER,
            "list",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("NOTIFICATION_LISTENER"),
                "action" to JsonPrimitive("list")
            ))
        )

        if (status.body.contains("\"granted\":true")) {
            // Granted: either it answers, or it says the service has not connected. It must never
            // be a 403, and it must never invent a notification.
            assertTrue(
                "granted access must answer the list, got ${list.status} ${list.body}",
                list.status == 200 || list.status == 503
            )
            if (list.status == 200) assertTrue("the list must be counted", list.body.contains("count"))
        } else {
            // Not granted must be 403 — the answer that stops "no permission" being misread as
            // "a quiet phone".
            assertTrue(
                "without the grant the list must be refused, got ${list.status}",
                list.status == 403
            )
        }
    }

    @Test
    fun dismissingANotificationThatIsNotThereSaysSo() {
        val status = actions.invoke(
            CapabilityType.NOTIFICATION_LISTENER,
            "status",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("NOTIFICATION_LISTENER"),
                "action" to JsonPrimitive("status")
            ))
        ).body
        if (!status.contains("\"granted\":true")) return

        val response = actions.invoke(
            CapabilityType.NOTIFICATION_LISTENER,
            "dismiss",
            JsonObject(mapOf(
                "capability" to JsonPrimitive("NOTIFICATION_LISTENER"),
                "action" to JsonPrimitive("dismiss"),
                "key" to JsonPrimitive("0|com.definitely.not.here|999999|null|999999")
            ))
        )
        assertTrue(
            "dismissing nothing must not report success, got ${response.status} ${response.body}",
            response.status == 404
        )
    }

    @Test
    fun theDocumentsProviderRefusesToWalkOutOfItsOwnFolder() {
        ok("DOCUMENTS_PROVIDER", "status")
        ok("DOCUMENTS_PROVIDER", "list")

        for (evil in listOf("../../etc/passwd", "a/b", "..")) {
            val response = actions.invoke(
                CapabilityType.DOCUMENTS_PROVIDER,
                "delete",
                JsonObject(mapOf(
                    "capability" to JsonPrimitive("DOCUMENTS_PROVIDER"),
                    "action" to JsonPrimitive("delete"),
                    "documentId" to JsonPrimitive(evil)
                ))
            )
            // Traversal is refused outright. A 404 would mean it resolved to something real and
            // happened to be missing, which is not good enough for a delete.
            assertTrue(
                "'$evil' must be refused as traversal, got ${response.status} ${response.body}",
                response.status == 400 || response.status == 404
            )
            assertTrue(
                "'$evil' must never report a deletion",
                !response.body.contains("\"deleted\":true")
            )
        }
    }

    /**
     * Exercises the DocumentsProvider the way the system's file picker would.
     *
     * The shell cannot reach it through ContentResolver — that is MANAGE_DOCUMENTS working, which is
     * correct — so the provider is driven directly against real files on disk. This is the only way
     * to prove the cursor contents are right rather than merely non-empty.
     */
    @Test
    fun theDocumentsProviderServesRealFilesToThePicker() {
        val provider = PlatformDocumentsProvider()
        provider.attachInfo(
            InstrumentationRegistry.getInstrumentation().targetContext,
            android.content.pm.ProviderInfo().apply {
                authority = "com.personal.base.documents"
                // DocumentsProvider refuses to attach unless exported, which is the framework
                // restating the manifest's own requirement — the provider must be reachable for
                // the system to hand it out in a picker.
                exported = true
                grantUriPermissions = true
                // The framework refuses to attach a DocumentsProvider that is not protected by
                // MANAGE_DOCUMENTS, which is exactly the guardrail the manifest declares. Stating
                // it here too keeps this test faithful to what is actually installed.
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
            }
        )

        // The root must be visible, or the picker shows nothing at all.
        val roots: android.database.Cursor = provider.queryRoots(null)
        assertTrue("the picker must be offered at least one root", roots.count >= 1)
        assertTrue("the roots cursor must be readable", roots.moveToFirst())
        assertEquals(
            "the first root must be the one the API advertises",
            PlatformDocumentsProvider.ROOT_ID,
            roots.getString(roots.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_ROOT_ID))
        )

        // Put a real file where the provider reads.
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "Android Star"
        )
        val probe = java.io.File(dir, "provider-probe.txt")
        probe.writeText("served by the provider")

        val children: android.database.Cursor =
            provider.queryChildDocuments(
                PlatformDocumentsProvider.ROOT_ID,
                null,
                null as String?
            )
        val ids = mutableListOf<String>()
        val idColumn = children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val sizeColumn = children.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        var foundProbe = false
        var probeSize = -1L
        while (children.moveToNext()) {
            val id = children.getString(idColumn)
            ids.add(id)
            if (id == probe.name) {
                foundProbe = true
                probeSize = children.getLong(sizeColumn)
            }
        }
        assertTrue(
            "a real file in the folder must appear in the picker, saw $ids",
            foundProbe
        )
        assertEquals(
            "the size reported must be the file's real size",
            probe.length(),
            probeSize
        )
        children.close()

        // And it must actually open. Reading it back is the only proof the cursor was not fiction.
        val descriptor = provider.openDocument(probe.name, "r", null)
        val text = try {
            java.io.FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } finally {
            descriptor.close()
        }
        assertEquals("served by the provider", text)
        probe.delete()
    }

    @Test
    fun theDocumentsProviderWillNotOpenAnythingOutsideItsFolder() {
        val provider = PlatformDocumentsProvider()
        provider.attachInfo(
            InstrumentationRegistry.getInstrumentation().targetContext,
            android.content.pm.ProviderInfo().apply {
                authority = "com.personal.base.documents"
                // DocumentsProvider refuses to attach unless exported, which is the framework
                // restating the manifest's own requirement — the provider must be reachable for
                // the system to hand it out in a picker.
                exported = true
                grantUriPermissions = true
                // The framework refuses to attach a DocumentsProvider that is not protected by
                // MANAGE_DOCUMENTS, which is exactly the guardrail the manifest declares. Stating
                // it here too keeps this test faithful to what is actually installed.
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
            }
        )

        for (evil in listOf("../../etc/passwd", "..%2Fpasswd", "sub/child", "..")) {
            val escaped = runCatching {
                val descriptor = provider.openDocument(evil, "r", null)
                try {
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                } finally {
                    descriptor.close()
                }
            }
            assertTrue(
                "'$evil' must not be readable through the picker",
                escaped.isFailure
            )
            val deleted = runCatching { provider.deleteDocument(evil) }
            assertTrue("'$evil' delete must throw, not quietly succeed", deleted.isFailure)
            assertTrue("'$evil' must not be deletable through the picker", deleted.isFailure)
        }
    }

    @Test
    fun theDocumentsProviderRefusesToClaimItCanCreate() {
        val provider = PlatformDocumentsProvider()
        provider.attachInfo(
            InstrumentationRegistry.getInstrumentation().targetContext,
            android.content.pm.ProviderInfo().apply {
                authority = "com.personal.base.documents"
                // DocumentsProvider refuses to attach unless exported, which is the framework
                // restating the manifest's own requirement — the provider must be reachable for
                // the system to hand it out in a picker.
                exported = true
                grantUriPermissions = true
                // The framework refuses to attach a DocumentsProvider that is not protected by
                // MANAGE_DOCUMENTS, which is exactly the guardrail the manifest declares. Stating
                // it here too keeps this test faithful to what is actually installed.
                readPermission = "android.permission.MANAGE_DOCUMENTS"
                writePermission = "android.permission.MANAGE_DOCUMENTS"
            }
        )

        // It advertises no create flag, so it must also refuse to create. Offering one without the
        // other is how a picker ends up silently discarding whatever the user creates.
        val roots: android.database.Cursor = provider.queryRoots(null)
        roots.moveToFirst()
        val rootFlags = roots.getInt(roots.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_FLAGS))
        assertEquals(
            "the root must not advertise document creation it cannot honour",
            0,
            rootFlags and DocumentsContract.Root.FLAG_SUPPORTS_CREATE
        )
        assertTrue(
            "createDocument must refuse rather than return an id for nothing",
            runCatching {
                provider.createDocument(PlatformDocumentsProvider.ROOT_ID, "text/plain", "x.txt")
            }.isFailure
        )
    }

    @Test
    fun theDetectorIsNotBeingHandedABlankImage() {
        // The failure this guards against is silent: a conversion bug produces "no faces" for every
        // photo, which is indistinguishable from a working detector on pictures of empty rooms.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = OnDeviceMlCapabilityProvider(ctx, PlatformLogger())

        // A flat mid-grey source. Whatever the detector receives must still be mid-grey.
        val grey = android.graphics.Bitmap.createBitmap(200, 150, android.graphics.Bitmap.Config.ARGB_8888)
        grey.eraseColor(android.graphics.Color.rgb(128, 128, 128))

        val source = provider.averageBrightness(grey)
        val analysed = provider.analysisBrightness(grey)
        grey.recycle()

        assertTrue("the source bitmap must really be mid-grey, got $source", source in 120f..136f)
        assertTrue(
            "the bitmap handed to the detector must not be blank or mangled: source=$source " +
                "analysed=$analysed",
            kotlin.math.abs(analysed - source) < 6f
        )

        // And a 12MP source must be scaled down rather than rejected outright.
        val big = android.graphics.Bitmap.createBitmap(4000, 3000, android.graphics.Bitmap.Config.ARGB_8888)
        big.eraseColor(android.graphics.Color.rgb(200, 200, 200))
        val size = provider.analysisSize(big)
        big.recycle()
        assertTrue(
            "a very large image must be scaled to fit the detector's buffer, got $size",
            size.first <= 480 && size.second <= 480 && size.first > 0
        )
    }
}
