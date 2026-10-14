package com.personal.base.core.app

import com.personal.base.core.platform.EmbeddedServer
import com.personal.base.core.platform.ServerRequest
import com.personal.base.core.platform.ServerResponse

/**
 * One endpoint an App publishes, with its documentation.
 *
 * Documentation is part of the definition, not a comment beside it. An App cannot expose an
 * endpoint without also describing it, and its description cannot drift from what it does.
 */
data class AppRoute(
    val path: String,
    val summary: String,
    val example: String = "",
    val requiresUserConsent: Boolean = false,
    val handler: (ServerRequest) -> ServerResponse
) {
    init {
        require(summary.isNotBlank()) { "Route '$path' must have a summary" }
    }
}

/**
 * Lets an App publish documented endpoints on the platform's server.
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

    private val registered = mutableListOf<AppRoute>()
    private val prefix = "/api/apps/$appId"

    /** The absolute path a relative App path resolves to. */
    fun resolve(path: String): String = "$prefix/${path.trimStart('/')}"

    fun get(
        path: String,
        summary: String,
        example: String = "",
        requiresUserConsent: Boolean = false,
        handler: (ServerRequest) -> ServerResponse
    ) = register("GET", path, summary, example, requiresUserConsent, handler)

    fun post(
        path: String,
        summary: String,
        example: String = "",
        requiresUserConsent: Boolean = false,
        handler: (ServerRequest) -> ServerResponse
    ) = register("POST", path, summary, example, requiresUserConsent, handler)

    /** Every route this App has published, for the docs endpoint. */
    fun routes(): List<AppRoute> = registered.toList()

    private fun register(
        method: String,
        path: String,
        summary: String,
        example: String,
        requiresUserConsent: Boolean,
        handler: (ServerRequest) -> ServerResponse
    ) {
        val full = resolve(path)
        val route = AppRoute(path, summary, example, requiresUserConsent, handler)
        server.registerRoute(full) { request ->
            // The toggle is checked per request, so switching the App off takes effect immediately
            // without the route being removed.
            if (!isEnabled()) {
                ServerResponse.unavailable("App '$appId' is switched off")
            } else {
                runCatching { handler(request) }
                    .getOrElse { ServerResponse(500, "application/json", """{"error":"${it.message}"}""") }
            }
        }
        registered += route
    }

    /** Removes every route this App registered. Called when the App stops. */
    fun uninstall() {
        registered.forEach {
            server.unregisterRoute(resolve(it.path))
            onUnregister(resolve(it.path))
        }
        registered.clear()
    }
}

/**
 * An App that publishes endpoints on the platform's server.
 *
 * The platform owns the server and the port; an App only contributes documented routes. That keeps
 * one socket for the whole device, keeps the always-on work inside the platform's foreground
 * service, and means an App never has to manage a listener of its own.
 *
 * [install] runs when the App is switched on, [uninstall] when it is switched off.
 */
interface AppServer {
    /** Publish this App's endpoints. Each one must carry a summary. */
    fun install(registrar: AppRouteRegistrar)

    /** Release anything the App opened while serving. */
    fun uninstall() {}
}
