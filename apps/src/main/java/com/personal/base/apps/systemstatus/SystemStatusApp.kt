package com.personal.base.apps.systemstatus

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.vector.ImageVector
import com.personal.base.core.app.AppContribution
import com.personal.base.core.app.PlatformApp
import com.personal.base.core.app.PlatformAppDefinition
import com.personal.base.core.app.WidgetSpec
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.plugin.PlatformContext

/**
 * A reference App whose whole point is the home-screen widget.
 *
 * It exists to prove the shape rather than to be useful: one Kotlin file, one annotation, and a
 * widget. There is no manifest entry, no `AppWidgetProvider`, and no layout — the platform's single
 * provider renders [WidgetSpec], exactly as it would for any App written later.
 *
 * The status line is read from real capabilities and refreshed on the platform's periodic tick, so
 * the tile shows something true rather than a placeholder.
 */
@PlatformAppDefinition(id = "system-status", name = "System Status", order = 10)
class SystemStatusApp : PlatformApp {

    override val id = "system-status"
    override val name = "System Status"
    override val icon: ImageVector = Icons.Default.Info
    override val description = "Battery, storage and connection at a glance on your home screen"
    override val version = "1.0"

    override val requiredCapabilities = setOf(
        CapabilityType.BATTERY_POWER,
        CapabilityType.STORAGE,
        CapabilityType.CONNECTIVITY
    )

    override val contribution = AppContribution(
        widget = WidgetSpec(
            id = "system-status",
            title = "System",
            subtitle = "Battery, storage, connection",
            status = "Starting…",
            icon = WidgetSpec.ICON_SYNC
        )
    )

    /** Held from initialisation, because a widget update is not tied to a composable. */
    private var platform: PlatformContext? = null

    override fun onInitialize(context: PlatformContext) {
        platform = context
        refresh()
    }

    /**
     * Republishes with a fresh status line.
     *
     * Only the status changes, so the tile is updated rather than replaced. Reading through the
     * facade means this App has no Android permission of its own and cannot do anything the
     * platform's switches would refuse.
     */
    fun refresh() {
        val ctx = platform ?: return

        val battery = ctx.getCapabilityByClass(
            com.personal.base.platform.capabilities.BatteryPowerCapabilityProvider::class.java
        )?.getBatteryPowerState()

        val storage = ctx.getCapabilityByClass(
            com.personal.base.platform.capabilities.StorageCapabilityProvider::class.java
        )?.getStorageTelemetry()

        val network = ctx.getCapabilityByClass(
            com.personal.base.platform.capabilities.ConnectivityCapabilityProvider::class.java
        )?.networkState?.value

        val parts = listOfNotNull(
            battery?.let { "${it.levelPercentage}%" },
            storage?.let { "%.0f GB free".format(it.internalAvailableGb) },
            network?.let { if (it.isConnected) "online" else "offline" }
        )

        ctx.publishWidget(
            contribution.widget?.copy(
                status = if (parts.isEmpty()) "No data yet" else parts.joinToString(" · ")
            )
        )
    }

    override suspend fun onPeriodicSync() {
        refresh()
    }
}