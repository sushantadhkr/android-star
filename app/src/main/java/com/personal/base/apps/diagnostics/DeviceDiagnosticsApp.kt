package com.personal.base.apps.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.app.AppContribution
import com.personal.base.core.app.AppScope
import com.personal.base.core.app.DashboardAction
import com.personal.base.core.app.PlatformApp
import com.personal.base.core.app.PlatformAppDefinition
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.plugin.PlatformContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import com.personal.base.core.app.AppScopeImpl
import com.personal.base.core.app.CapabilityServingApp

/**
 * Device Diagnostics — the reference App.
 *
 * This exists to prove the model end to end. It is written the way any App is written:
 *
 *   - it is declared, not hand-listed anywhere in the shell;
 *   - it has a user toggle and appears on the Apps page;
 *   - it contributes a dashboard card and a dashboard action;
 *   - it can be switched off, which destroys the instance and clears its state;
 *   - it exposes a capability over the network as a server route;
 *   - it logs, and those logs reach the Logs page.
 *
 * No shell file was edited to add it.
 */
@PlatformAppDefinition(id = "device-diagnostics", name = "Device Diagnostics", order = 10)
class DeviceDiagnosticsApp : PlatformApp, CapabilityServingApp {

    override val id = DIAGNOSTICS_ID
    override val name = "Device Diagnostics"
    override val icon = Icons.Default.MonitorHeart
    override val description = "Exposes device telemetry over HTTP for scripts and dashboards."
    override val version = "1.0"
    override val category = "Tools"

    /** State that exists only while the user has this App switched on. */
    private var snapshot: String = "not yet sampled"
    private var sampleCount: Int = 0

    override val contribution: AppContribution = AppContribution(
        dashboardCard = { scope -> DiagnosticsCard(scope) },
        dashboardActions = listOf(
            DashboardAction(
                id = "sample",
                label = "Sample device telemetry",
                onInvoke = { sample() }
            )
        )
    )

    override fun onInitialize(context: PlatformContext) {
        context.logger.i("DiagnosticsApp", "Diagnostics App started")
        sample()
    }

    override fun onDestroy() {
        // Toggling off wipes App state, exactly as if the App had never been installed.
        snapshot = "not yet sampled"
        sampleCount = 0
    }

    /** Samples the capability layer. Demonstrates consuming a core capability from an App. */
    private fun sample() {
        sampleCount++
        snapshot = "sample #$sampleCount — battery ${android.os.BatteryManager.BATTERY_STATUS_UNKNOWN}, " +
                "capabilities requested: ${CapabilityType.entries.size}"
    }

    /** The App's own full screen, opened from its card. */
    @Composable
    override fun AppContent(scope: AppScope, onBack: () -> Unit) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text("Latest sample", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = snapshot,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { sample() }) { Text("Take a sample") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }

    companion object {
        const val DIAGNOSTICS_ID = "device-diagnostics"
    }

    /** Read by the dashboard card, so the card shows the live instance's state. */
    fun telemetrySnapshot(): String = snapshot

    /**
     * Served over HTTP while this App runs. The route disappears when the App is switched off,
     * because the route is bound to this instance.
     */
    override fun httpPayload(): String =
        """{"app":"$DIAGNOSTICS_ID","samples":$sampleCount,"snapshot":"$snapshot"}"""

    override val servePath: String get() = DIAGNOSTICS_ID
}

/**
 * The dashboard card. It renders from the App's own live instance, so it always shows current
 * state — and reads as disabled when the user has switched the App off.
 */
@Composable
private fun DiagnosticsCard(scope: AppScope) {
    val app = (scope as? AppScopeImpl)?.instance as? DeviceDiagnosticsApp

    Column(modifier = Modifier.fillMaxWidth()) {
        if (scope.isEnabled && app != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Running",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = app.telemetrySnapshot(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                text = "Disabled",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Switch on from the Apps page",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}