package com.personal.base.core.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A widget, described as data.
 *
 * A home-screen widget cannot render Compose, so every widget would otherwise have to be written
 * twice — once as a Card and again in RemoteViews. Declaring it as plain data instead means the
 * platform renders it and an App never touches a RemoteViews, an XML layout, or the manifest.
 *
 * That matters for the same reason `AppContribution` does: adding an App must never mean editing
 * the shell. One widget provider is declared once by the platform and renders whatever is
 * published here.
 */
data class WidgetSpec(
    /** Stable and unique. Re-publishing with the same id updates the tile rather than duplicating it. */
    val id: String,

    /** The bold line. Kept short — a home screen is not a dashboard. */
    val title: String,

    /** The second line. */
    val subtitle: String = "",

    /** A single live line of state, such as "4 of 40 copied". */
    val status: String = "",

    /** One of [WIDGET_ICONS]. Anything else falls back to a neutral icon. */
    val icon: String? = null,

    /** 0-100, or null for no bar. A negative value is rejected rather than clamped. */
    val progress: Int? = null,

    /** Where tapping goes. Null opens the App's own screen. */
    val route: String? = null
) {
    init {
        require(id.isNotBlank()) { "A widget needs an id" }
        require(title.isNotBlank()) { "Widget '$id' needs a title" }
        require(title.length <= 40) { "Widget '$id' has a title too long for a home screen" }
        require(progress == null || progress in 0..100) {
            "Widget '$id' has a progress of $progress, which is not 0-100"
        }
    }

    companion object {
        /**
         * Icons the platform can draw.
         *
         * Framework drawables rather than an App's, because an App cannot contribute a drawable
         * resource to the shell — the same reasoning as notification button icons.
         */
        const val ICON_CHECK = "check"
        const val ICON_ALARM = "alarm"
        const val ICON_SYNC = "sync"
        const val ICON_DOWNLOAD = "download"
        const val ICON_CAMERA = "camera"
        const val ICON_MIC = "mic"
        const val ICON_LOCATION = "location"
        const val ICON_SMS = "sms"
        const val ICON_STORAGE = "storage"
        const val ICON_NETWORK = "network"

        val WIDGET_ICONS = setOf(
            ICON_CHECK, ICON_ALARM, ICON_SYNC, ICON_DOWNLOAD, ICON_CAMERA,
            ICON_MIC, ICON_LOCATION, ICON_SMS, ICON_STORAGE, ICON_NETWORK
        )
    }
}

/** A widget with the App that owns it and whether that App is currently on. */
data class PublishedWidget(
    val appId: String,
    val spec: WidgetSpec,
    val isEnabled: Boolean
)

/**
 * Everything currently published as a home-screen widget.
 *
 * Keyed per App so an App publishing twice updates its own tile, and so turning an App off can
 * withdraw its widget without touching anyone else's. That is the same lifecycle rule the App
 * toggle already follows everywhere else.
 */
class WidgetRegistry {

    private val _widgets = MutableStateFlow<List<PublishedWidget>>(emptyList())
    val widgets: StateFlow<List<PublishedWidget>> = _widgets.asStateFlow()

    /** Publishes or replaces an App's widget. A null spec withdraws it. */
    fun publish(appId: String, spec: WidgetSpec?, isEnabled: Boolean = true) {
        val existing = _widgets.value.filterNot { it.appId == appId }
        _widgets.value = if (spec == null) {
            existing
        } else {
            (existing + PublishedWidget(appId, spec, isEnabled)).sortedBy { it.appId }
        }
    }

    /** Mirrors the App's toggle, so a switched-off App's widget reads as off rather than lying. */
    fun setEnabled(appId: String, enabled: Boolean) {
        _widgets.value = _widgets.value.map {
            if (it.appId == appId) it.copy(isEnabled = enabled) else it
        }
    }

    fun withdraw(appId: String) = publish(appId, null)

    fun forApp(appId: String): PublishedWidget? = _widgets.value.firstOrNull { it.appId == appId }

    /** Only the widgets a switched-on App owns, which is what belongs on the home screen. */
    fun visible(): List<PublishedWidget> = _widgets.value.filter { it.isEnabled }

    fun size(): Int = _widgets.value.size

    fun clear() {
        _widgets.value = emptyList()
    }
}