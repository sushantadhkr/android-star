package com.personal.base.core.app

import androidx.compose.ui.graphics.vector.ImageVector
import com.personal.base.core.capability.CapabilityType

/**
 * Marks a class as a Platform App.
 *
 * An App is discovered at build time and its registration is generated automatically, so adding an
 * App never requires editing the shell. The annotated class is the App's *factory*: it is
 * instantiated only when the user switches the App on.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class PlatformAppDefinition(
    /** Stable unique id. Used for toggle persistence, so it must not change. */
    val id: String,
    /** Display name on the Apps page. */
    val name: String,
    /** Sort weight on the Apps page. Lower sorts earlier. */
    val order: Int = 100
)

/**
 * Everything the platform knows about an App *before* any instance exists.
 *
 * This is deliberately separate from [PlatformApp]: metadata and contributions are always known so
 * every App can appear on the Apps page with a working toggle, while the live instance exists only
 * while the user has the App switched on.
 */
data class AppRegistration(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val icon: ImageVector,
    val category: String,
    val requiredCapabilities: Set<CapabilityType>,
    val order: Int,
    /** Creates the App instance. Called on enable, discarded on disable. */
    val factory: () -> PlatformApp
)

/**
 * What an App declared it contributes, captured when it last ran.
 *
 * This snapshot is what lets a stopped App's dashboard items survive its teardown: the arrangement
 * the user built is preserved and the items simply read as disabled.
 */
data class DeclaredContribution(
    val hasCard: Boolean = false,
    val actionLabels: List<Pair<String, String>> = emptyList()
)

/**
 * An App as the platform sees it: its declared registration, its persisted toggle, and — only when
 * enabled — its live instance.
 */
data class AppEntry(
    val registration: AppRegistration,
    val isEnabled: Boolean,
    val instance: PlatformApp?,
    val declared: DeclaredContribution = DeclaredContribution()
) {
    val id: String get() = registration.id
    val name: String get() = registration.name

    /** A registered App is always listed; it is only ever *running* when enabled. */
    val isRunning: Boolean get() = instance != null
}