package com.personal.base.core.app

import com.personal.base.core.platform.EmbeddedServer
import com.personal.base.core.platform.ServerRequest
import com.personal.base.core.platform.ServerResponse

/**
 * Lets an App publish endpoints on the platform's server.
 *
 * Routes are namespaced under the App, so two Apps can never collide. Every route is gated on the
 * App's toggle: a request to a stopped App is answered with 503 rather than served, which is the
 * user-facing safety switch expressed over HTTP.
 */
class AppRouteRegistrar(
    val appId: String,
    private val server: EmbeddedServer,
    private val isEnabled: () -> Boolean,
    private val onUnregister: (String) -> Unit = {}
) {

    /** Every path this App has registered, so they can all be removed on teardown. */
    private val registered = mutableListOf<String>()

    private val prefix = "/api/apps/$appId"

    /** The absolute path a relative App path resolves to. */
    fun resolve(path: String): String = "$prefix/${path.trimStart('/')}"

    fun get(path: String, handler: (ServerRequest) -> ServerResponse) =
        register("GET", path, handler)

    fun post(path: String, handler: (ServerRequest) -> ServerResponse) =
        register("POST", path, handler)

    private fun register(method: String, path: String, handler: (ServerRequest) -> ServerResponse) {
        val full = resolve(path)
        server.registerRoute(full) { request ->
            // The toggle is checked per request, not per registration, so switching the App off
            // takes effect immediately without touching the server.
            if (!isEnabled()) {
                ServerResponse.unavailable("App '$appId' is switched off")
            } else {
                runCatching { handler(request) }
                    .getOrElse { ServerResponse(500, "application/json", """{"error":"${it.message}"}""") }
            }
        }
        registered += full
    }

    /** Removes every route this App registered. Called when the App stops. */
    fun uninstall() {
        registered.forEach {
            server.unregisterRoute(it)
            onUnregister(it)
        }
        registered.clear()
    }
}

/**
 * An App that publishes endpoints on the platform's server.
 *
 * The platform owns the server and the port; an App only contributes routes. That keeps one socket
 * for the whole device, keeps the always-on work inside the platform's foreground service, and
 * means an App never has to manage a listener of its own.
 *
 * [install] runs when the App is switched on, [uninstall] when it is switched off.
 */
interface AppServer {
    /** Publish this App's endpoints. */
    fun install(registrar: AppRouteRegistrar)

    /** Release anything the App opened while serving. */
    fun uninstall() {}
}
