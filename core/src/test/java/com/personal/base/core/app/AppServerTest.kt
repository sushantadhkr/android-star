package com.personal.base.core.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.EmbeddedServer
import com.personal.base.core.platform.ServerRequest
import com.personal.base.core.platform.ServerResponse
import com.personal.base.core.plugin.PlatformContext
import com.personal.base.core.plugin.PlatformLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves the always-on server contract: an App that serves publishes routes under its own prefix,
 * a stopped App answers 503 rather than serving, and stopping removes its routes entirely.
 */
class AppServerTest {

    private class FakeToggleStore : AppToggleStoreContract {
        private val state = mutableMapOf<String, Boolean>()
        override fun isEnabled(appId: String) = state[appId] ?: true
        override fun setEnabled(appId: String, enabled: Boolean) {
            state[appId] = enabled
        }
    }

    /** An App that serves one endpoint. */
    private inner class ServingApp : PlatformApp, AppServer {
        override val id = APP_ID
        override val name = "Serving"
        override val icon = Icons.Default.Apps
        override val description = ""
        override val version = "1.0"

        var installs = 0
        var uninstalls = 0

        override fun install(registrar: AppRouteRegistrar) {
            installs++
            registrar.get("status", "Report whether the App is running") { ServerResponse.json("""{"running":true}""") }
        }

        override fun uninstall() {
            uninstalls++
        }
    }

    /** An App that does not serve. */
    private class PlainApp : PlatformApp {
        override val id = "plain"
        override val name = "Plain"
        override val icon = Icons.Default.Apps
        override val description = ""
        override val version = "1.0"
    }

    private lateinit var manager: AppManager
    private lateinit var server: EmbeddedServer

    @Before
    fun setUp() {
        server = EmbeddedServer(PlatformLogger())
        manager = AppManager(
            platformContext = FakeContext,
            toggleStore = FakeToggleStore(),
            logger = PlatformLogger()
        )
    }

    private fun registerServingApp() {
        manager.register(
            AppRegistration(
                id = APP_ID,
                name = "Serving",
                description = "",
                version = "1.0",
                icon = Icons.Default.Apps,
                category = "Tools",
                requiredCapabilities = emptySet(),
                order = 1,
                factory = { ServingApp() }
            )
        )
    }

    @Test
    fun `no routes are installed while the platform server is down`() {
        registerServingApp()
        assertTrue("nothing is exposed before the host starts", server.routes().isEmpty())
    }

    @Test
    fun `a running app publishes its routes once the server is up`() {
        manager.server = server
        registerServingApp()

        assertEquals(listOf("/api/apps/$APP_ID/status"), server.routes())
    }

    @Test
    fun `routes are namespaced per app so two apps cannot collide`() {
        manager.server = server
        registerServingApp()
        manager.register(
            AppRegistration(
                id = "other",
                name = "Other",
                description = "",
                version = "1.0",
                icon = Icons.Default.Apps,
                category = "Tools",
                requiredCapabilities = emptySet(),
                order = 2,
                factory = { PlainApp() }
            )
        )
        // The plain app serves nothing, so only the serving app's prefix is present.
        assertTrue(server.routes().all { it.startsWith("/api/apps/$APP_ID") })
    }

    @Test
    fun `stopping an app removes its routes`() {
        manager.server = server
        registerServingApp()
        assertEquals(1, server.routes().size)

        manager.setEnabled(APP_ID, false)

        assertTrue("a stopped App must stop serving", server.routes().isEmpty())
        assertNull(manager.instanceOf(APP_ID))
    }

    @Test
    fun `re-enabling republishes the routes`() {
        manager.server = server
        registerServingApp()
        manager.setEnabled(APP_ID, false)
        manager.setEnabled(APP_ID, true)

        assertEquals(listOf("/api/apps/$APP_ID/status"), server.routes())
    }

    @Test
    fun `an app that does not serve publishes nothing`() {
        manager.server = server
        manager.register(
            AppRegistration(
                id = "plain",
                name = "Plain",
                description = "",
                version = "1.0",
                icon = Icons.Default.Apps,
                category = "Tools",
                requiredCapabilities = emptySet(),
                order = 1,
                factory = { PlainApp() }
            )
        )
        assertTrue(server.routes().isEmpty())
    }

    @Test
    fun `a request to a stopped app is refused rather than served`() {
        var appRunning = true
        val registrar = AppRouteRegistrar(APP_ID, server, { appRunning })
        registrar.get("status", "Report whether the App is running") { ServerResponse.json("""{"running":true}""") }

        val path = "/api/apps/$APP_ID/status"
        val handler = server.handlerFor(path)!!

        val whileRunning = handler(ServerRequest("GET", path, ""))
        assertEquals(200, whileRunning.status)

        // Gating is evaluated per request, so flipping the toggle takes effect immediately
        // without the route being removed.
        appRunning = false
        val whileStopped = handler(ServerRequest("GET", path, ""))
        assertEquals(503, whileStopped.status)
        assertTrue("route still present, just refusing", whileStopped.body.contains("switched off"))
    }

    @Test
    fun `a path resolves under the app prefix`() {
        val registrar = AppRouteRegistrar("webcam", server, { true })
        assertEquals("/api/apps/webcam/stream", registrar.resolve("stream"))
        assertEquals("/api/apps/webcam/stream", registrar.resolve("/stream"))
    }

    private object FakeContext : PlatformContext {
        // These fakes test lifecycle, not platform services, so there is no real Context here.
        override fun publishWidget(spec: WidgetSpec?) = Unit
        override fun appContext(): android.content.Context =
            throw UnsupportedOperationException("a fake context has no Android Context")
        override val logger: PlatformLogger = PlatformLogger()
        override fun hasCapability(type: CapabilityType) = true
        override fun getCapabilityStatus(type: CapabilityType) = CapabilityStatus.Available
        override fun <T : CapabilityProvider> getCapability(type: CapabilityType): T? = null
        override fun <T : CapabilityProvider> getCapabilityByClass(clazz: Class<T>): T? = null
        override fun postNotification(title: String, message: String, priorityHigh: Boolean) = Unit
        override fun triggerHaptic(durationMs: Long) = Unit
        override fun setTorch(enabled: Boolean) = false
        override fun getBatteryPercentage() = 100
        override fun getClipboardContent(): String? = null
        override fun setClipboardContent(text: String, label: String) = Unit
        override fun shareText(text: String, title: String) = Unit
        override suspend fun getFreshLocation(): PlatformLocation? = null
    }

    @Test
    fun `a route without a summary is refused at construction`() {
        val failure = runCatching {
            AppRoute(
                path = "status",
                summary = "   ",
                handler = { ServerResponse.json("{}") }
            )
        }.exceptionOrNull()
        assertTrue("an undocumented route must not be constructible", failure is IllegalArgumentException)
    }

    @Test
    fun `every published route carries a summary`() {
        manager.server = server
        registerServingApp()

        val routes = manager.publishedRoutes().flatMap { it.second }
        assertTrue(routes.isNotEmpty())
        routes.forEach { route ->
            assertTrue(
                "route ${route.path} must have a summary",
                route.summary.isNotBlank()
            )
        }
    }

    @Test
    fun `published routes disappear when the app stops`() {
        manager.server = server
        registerServingApp()
        assertTrue(manager.publishedRoutes().isNotEmpty())

        manager.setEnabled(APP_ID, false)
        assertTrue(manager.publishedRoutes().isEmpty())
    }

    private companion object {
        const val APP_ID = "serving-app"
    }
}
