package com.personal.base.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The platform's top-level pages.
 *
 * Capabilities is deliberately absent: it is a pinned Platform surface reached from Settings, not
 * a peer page.
 */
enum class NavigationDestination(
    val title: String,
    val icon: ImageVector
) {
    DASHBOARD("Dashboard", Icons.Default.Dashboard),
    APPS("Apps", Icons.Default.Apps),
    LOGS("Logs", Icons.Default.Article),
    SETTINGS("Settings", Icons.Default.Settings)
}

/**
 * Screens reachable from within a top-level page rather than from the page bar.
 *
 * An overlay behaves as a page of its own: the page bar is hidden while it is open, and it carries
 * its own title so the header never claims to be showing something else.
 */
enum class OverlayDestination(val title: String) {
    CAPABILITIES("Capabilities")
}
