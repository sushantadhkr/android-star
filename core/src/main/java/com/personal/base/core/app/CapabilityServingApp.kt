package com.personal.base.core.app

/**
 * An App that exposes a capability over the network.
 *
 * This is how a capability becomes a service — a webcam App serving frames, a diagnostics App
 * serving telemetry. The route only exists while the App is running: switching it off tears down
 * the instance and the route with it.
 *
 * The payload is read from the live instance, so it always reflects current state rather than a
 * snapshot taken at startup.
 */
interface CapabilityServingApp {
    /** JSON describing what this App is currently serving. */
    fun httpPayload(): String

    /** The path this App serves on, e.g. "camera" or "diagnostics". Defaults to the App id. */
    val servePath: String get() = "app"
}