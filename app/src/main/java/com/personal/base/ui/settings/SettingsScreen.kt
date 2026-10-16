package com.personal.base.ui.settings

import android.widget.Toast
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.app.AppScopeImpl
import com.personal.base.core.security.LockTimeout
import com.personal.base.core.runtime.NotificationGroup
import com.personal.base.di.PlatformContainer
import com.personal.base.platform.capabilities.PlatformAccessibilityService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    container: PlatformContainer,
    onOpenCapabilities: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** Bumped on every resume, so grants made in Settings elsewhere show up here on return. */
    var refreshCounter by remember { mutableStateOf(0) }

    val currentConfig by container.configRepository.config.collectAsState()

    // Read at the top level because two cards below both need them: the host card warns about
    // dozing, and the CPU card explains whether a wake lock would help.
    val ignoringOptimisations = container.wakeLockManager.isIgnoringBatteryOptimizations
    val keepCpuAwake = currentConfig.keepCpuAwakeWhileHostRuns
    val bgStatus by container.backgroundManager.status.collectAsState()
    val channels by container.notificationChannelManager.channelsFlow.collectAsState()
    val apps by container.appManager.apps.collectAsState()
    val lockTimeout by container.deviceLockGate.policyTimeout.collectAsState()

    var isForegroundEnabled by remember(currentConfig) { mutableStateOf(currentConfig.isForegroundServiceEnabled) }
    var isExporting by remember { mutableStateOf(false) }
    var settingsFilter by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // The shell's app bar already names this page, so it does not repeat the title here.

        // The token that guards the HTTP surface. Shown so it can be shared, and rotatable so it can be
        // replaced if it ever leaks.
        ApiTokenCard(container.apiTokenStore)

        Spacer(modifier = Modifier.height(8.dp))

        // Capabilities are reached from here — a pinned Platform surface, not a top-level page.
        OutlinedButton(
            onClick = onOpenCapabilities,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Extension, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Capabilities")
        }

        OutlinedTextField(
            value = settingsFilter,
            onValueChange = { settingsFilter = it },
            label = { Text("Filter settings") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // App-contributed settings. Distinct from platform settings above: these belong to
        // individual Apps and are scoped to their own instance.
        val appSections = remember(apps, settingsFilter) {
            apps.mapNotNull { entry ->
                val section = entry.instance?.contribution?.settingsSection
                    ?: return@mapNotNull null
                if (settingsFilter.isBlank() || entry.name.contains(settingsFilter, ignoreCase = true)) {
                    entry to section
                } else {
                    null
                }
            }
        }
        appSections.forEach { (entry, section) ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    section(
                        AppScopeImpl(
                            container.appManager,
                            entry.id,
                            container.platformContext,
                            container.notificationChannelManager,
                            container.logger
                        )
                    )
                }
            }
        }

        // 1. Foreground Service Configuration
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Device Lock",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A pinned platform surface. No app can disable it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Lock after",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                LockTimeout.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = lockTimeout == option,
                            onClick = { container.deviceLockGate.setTimeout(option) }
                        )
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // 2. Foreground Service Configuration
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Always-on Platform Host",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (bgStatus.isForegroundServiceRunning) {
                                    "Running · serving port ${container.embeddedServer.port}"
                                } else {
                                    "Stopped · phone unreachable"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (bgStatus.isForegroundServiceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Switch(
                        checked = isForegroundEnabled,
                        onCheckedChange = { enabled ->
                            isForegroundEnabled = enabled
                            container.backgroundManager.setForegroundServiceEnabled(enabled)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Keeps the platform running after you leave the app, holding a " +
                            "permanent notification so Android does not kill it. While it runs, " +
                            "your apps stay reachable over the network on port " +
                            "${container.embeddedServer.port} and background work keeps happening " +
                            "with the screen off. Turn it off and your phone becomes unreachable " +
                            "from the network entirely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Android dozes an idle app regardless of the wake lock, so without this exemption
                // the host goes quiet again shortly after the screen is off. Said plainly, because
                // the symptom — "the server died" — points nowhere near this switch.
                val ignoringOptimisations = container.wakeLockManager.isIgnoringBatteryOptimizations
                if (bgStatus.isForegroundServiceRunning && !ignoringOptimisations) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Android is still putting this app to sleep when the screen is off. " +
                                "The phone may stop answering within a minute of the screen going " +
                                "dark, even though the host says it is running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }) { Text("Allow background activity") }

                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }) { Text("Open list") }
                    }
                }
            }
        }

        // Keeping the CPU awake is its own switch, not part of the host toggle.
        //
        // It used to be unconditional, justified by a comment claiming the phone "stops responding
        // within a minute" of the screen going off. Measured on this device, that claim was wrong:
        // with the screen off and no wake lock at all, the host answered 12 of 12 requests in about
        // 20 ms. So it now defaults to off, costs nothing, and is offered for the one case that
        // genuinely needs it — a phone whose battery-optimisation exemption has been revoked.
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Keep CPU Awake",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when {
                                keepCpuAwake -> "On. The CPU is held awake while the host runs."
                                !ignoringOptimisations ->
                                    "Off, and Android may doze the host. Turn this on if the phone " +
                                        "stops answering once the screen is dark."
                                else -> "Off. The host relies on Android waking it, which is enough."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = keepCpuAwake,
                        onCheckedChange = { enabled ->
                            container.configRepository.updateConfig {
                                it.copy(keepCpuAwakeWhileHostRuns = enabled)
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Turning this on stops Android suspending the CPU, so the phone never " +
                            "sleeps. It is rarely needed and it costs battery all day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Screen control. Onboarding asks for this once, but onboarding is a one-time screen and
        // a user who skips past it needs a way back — otherwise the capability looks broken with
        // no route to fix it.
        val accessibilityGranted = remember(refreshCounter) {
            PlatformAccessibilityService.isEnabledFor(context)
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Screen control",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (accessibilityGranted) {
                                "On. Apps can tap and swipe your screen."
                            } else {
                                "Off. Taps, swipes and the Remote Input capability do nothing until this is on."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (accessibilityGranted) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text(if (accessibilityGranted) "Manage" else "Enable") }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Android only grants this from system settings. It lets Android Star tap " +
                            "and swipe; it cannot read what is on screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Grants are made in another app, so this screen gets no callback for them. Re-reading on
    // resume is what keeps this card honest the moment the user comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshCounter++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Downloads: where files the platform fetches are written.
        var downloadFolder by remember { mutableStateOf(container.downloadLocation.folderName) }

        val pickFolder = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null && container.downloadLocation.setFolder(uri)) {
                downloadFolder = container.downloadLocation.folderName
            }
        }

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Downloads folder",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = downloadFolder ?: "Downloads / Android Star (the default)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "By default files land in the phone's own Downloads, which needs no " +
                            "permission on Android 10 and up. Point this somewhere else — an SD " +
                            "card, a synced folder — and the platform will write there instead. " +
                            "The folder is chosen through Android's own picker, so no storage " +
                            "permission is involved at any version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) {
                        Text(if (downloadFolder == null) "Choose a folder" else "Change folder")
                    }
                    if (downloadFolder != null) {
                        OutlinedButton(onClick = {
                            container.downloadLocation.clear()
                            downloadFolder = null
                        }) { Text("Use the default") }
                    }
                }
            }
        }

        // 2. Biometric & Device Security Gate
        val isBiometricLockEnabled by container.biometricProvider.isLockEnabled.collectAsState()
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Device Security & Biometric Lock",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = container.biometricProvider.statusDescription,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isBiometricLockEnabled,
                        onCheckedChange = { enabled ->
                            container.biometricProvider.setLockEnabled(enabled)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Locks the entire platform behind your device's biometric sensor or secure lock screen (PIN, Pattern, Password).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isBiometricLockEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { container.biometricProvider.lockNow() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lock Platform Now")
                    }
                }
            }
        }

        // 3. Categorized & Per-App Notification Controls
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Platform alerts and App alerts are kept apart. Silencing an App never " +
                            "silences the platform, and vice versa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                NotificationGroup.entries.forEach { group ->
                    val groupChannels = channels.filter { it.group == group }
                    if (groupChannels.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (group == NotificationGroup.PLATFORM) {
                                    Icons.Default.Shield
                                } else {
                                    Icons.Default.Extension
                                },
                                contentDescription = null,
                                tint = if (group == NotificationGroup.PLATFORM) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.secondary
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = group.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = group.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        groupChannels.forEach { ch ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ch.channelName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = ch.channelDescription,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = ch.isEnabled,
                                    onCheckedChange = { enabled ->
                                        container.notificationChannelManager.setChannelEnabled(ch.channelId, enabled)
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }

        // 4. OEM Keep-Alive & Diagnostics Bundle
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("System Keep-Alive & Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                val oem = container.oemKeepAlive
                Text(
                    text = "OEM Vendor: ${oem.detectedVendor.name} • Autostart guidance: ${if (oem.hasAggressiveKillPolicy) "Aggressive Kill Detected" else "Standard Android"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (oem.hasAggressiveKillPolicy) {
                    OutlinedButton(
                        onClick = { oem.openAutostartSettings() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Configure OEM Autostart & Battery Protection")
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            isExporting = true
                            try {
                                val zip = container.diagnosticsBundle.generateDiagnosticsZip()
                                Toast.makeText(context, "Exported: ${zip.name}", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isExporting
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isExporting) "Bundling Diagnostics..." else "Export Diagnostics Bundle (ZIP)")
                }
            }
        }
    }
}
