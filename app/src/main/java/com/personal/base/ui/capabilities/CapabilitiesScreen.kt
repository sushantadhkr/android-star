package com.personal.base.ui.capabilities

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityEntry
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.plugin.PlatformLocation
import com.personal.base.di.PlatformContainer
import com.personal.base.platform.capabilities.LocationCapabilityProvider
import com.personal.base.platform.capabilities.SensorsCapabilityProvider
import kotlinx.coroutines.launch

@Composable
fun CapabilitiesScreen(
    container: PlatformContainer,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val capabilitiesState by container.capabilityRegistry.capabilitiesState.collectAsState()
    val catalogue by container.capabilityCatalogue.entries.collectAsState()
    val masterEnabled by container.capabilityCatalogue.masterEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { container.capabilityCatalogue.refresh() }

    // Sensor state
    val sensorsProvider = container.capabilityRegistry.getProvider<SensorsCapabilityProvider>(CapabilityType.SENSORS)
    val sensorData by (sensorsProvider?.sensorData ?: remember { mutableStateOf(com.personal.base.platform.capabilities.SensorSnapshot()) }).let {
        sensorsProvider?.sensorData?.collectAsState() ?: remember { mutableStateOf(com.personal.base.platform.capabilities.SensorSnapshot()) }
    }

    // Location state
    val locationProvider = container.capabilityRegistry.getProvider<LocationCapabilityProvider>(CapabilityType.LOCATION)
    val lastLocation by (locationProvider?.lastLocation ?: remember { mutableStateOf<PlatformLocation?>(null) }).let {
        locationProvider?.lastLocation?.collectAsState() ?: remember { mutableStateOf<PlatformLocation?>(null) }
    }
    var isFetchingLocation by remember { mutableStateOf(false) }

    // Clipboard test state
    var clipboardInput by remember { mutableStateOf("Personal Base Secret Payload 42") }
    var readClipboardValue by remember { mutableStateOf("") }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        container.permissionManager.refreshPermissions()
        container.capabilityRegistry.refreshStatus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Back navigation is provided by the shell's app bar, so this page does not repeat it.
        Text(
            text = "Every capability the platform offers. Unavailable ones stay listed and greyed " +
                    "out rather than hidden, so the full surface is always discoverable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // The network surface: where this data comes from, and how to call it from a script.
        ApiReferenceCard(container.apiTokenStore)

        // Pinned Platform surface: the master switch governs every capability at once.
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Capabilities API",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (masterEnabled) {
                            "Capabilities are exposed and available to Apps."
                        } else {
                            "All capabilities are switched off."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = masterEnabled,
                    onCheckedChange = { container.capabilityCatalogue.setMaster(it) }
                )
            }
        }

        // Every capability is listed. Anything not currently usable is greyed out rather than
        // hidden, so the full surface stays discoverable.
        Text(
            text = "All capabilities (${catalogue.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                catalogue.forEach { entry ->
                    val alpha = if (entry.isUsable) 1f else 0.4f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.type.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                            )
                            Text(
                                text = blockedReason(entry),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                            )
                        }
                        Switch(
                            checked = entry.isSwitchedOn && masterEnabled,
                            enabled = masterEnabled,
                            onCheckedChange = {
                                container.capabilityCatalogue.setCapability(entry.type, it)
                            }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }

        // Missing permissions callout if any
        val deniedCapabilities = capabilitiesState.filter { it.value is CapabilityStatus.PermissionRequired }
        if (deniedCapabilities.isNotEmpty()) {
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Permissions Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Some capabilities are restricted until runtime permissions are granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val perms = deniedCapabilities.values
                                .filterIsInstance<CapabilityStatus.PermissionRequired>()
                                .flatMap { it.missingPermissions }
                                .distinct()
                                .toTypedArray()
                            if (perms.isNotEmpty()) {
                                permissionLauncher.launch(perms)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant Required Permissions")
                    }
                }
            }
        }

        // 1. Hardware Sensors Card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sensors Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Badge {
                        Text("${sensorData.availableSensorsCount} Detected")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Accelerometer (m/s²): X: ${"%.2f".format(sensorData.accelX)} | Y: ${"%.2f".format(sensorData.accelY)} | Z: ${"%.2f".format(sensorData.accelZ)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Gyroscope (rad/s): X: ${"%.2f".format(sensorData.gyroX)} | Y: ${"%.2f".format(sensorData.gyroY)} | Z: ${"%.2f".format(sensorData.gyroZ)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Ambient Light: ${if (sensorData.lightLux >= 0) "${sensorData.lightLux} lx" else "N/A"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Barometer: ${if (sensorData.pressureHpa >= 0) "${sensorData.pressureHpa} hPa" else "N/A"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 2. Geographic Location Card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Location Services", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    StatusPill(status = container.capabilityRegistry.getStatus(CapabilityType.LOCATION))
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                if (lastLocation != null) {
                    Text(
                        text = "Lat: ${lastLocation?.latitude}, Lon: ${lastLocation?.longitude}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Accuracy: ±${lastLocation?.accuracyMeters}m • Provider: ${lastLocation?.provider}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "No location fix acquired yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            isFetchingLocation = true
                            container.platformContext.getFreshLocation()
                            isFetchingLocation = false
                        }
                    },
                    enabled = !isFetchingLocation
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isFetchingLocation) "Acquiring Fix..." else "Query Fresh Location")
                }
            }
        }

        // 3. Notification Dispatcher Card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    StatusPill(status = container.capabilityRegistry.getStatus(CapabilityType.NOTIFICATIONS))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Post alerts across system channels with Android 13+ permission compliance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            container.platformContext.postNotification(
                                title = "Personal Base Alert",
                                message = "Test notification posted at ${System.currentTimeMillis()}",
                                priorityHigh = true
                            )
                        }
                    ) {
                        Text("Post High-Priority Alert")
                    }
                }
            }
        }

        // 4. Clipboard & Sharing Card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clipboard & Sharing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = clipboardInput,
                    onValueChange = { clipboardInput = it },
                    label = { Text("Clipboard Payload") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            container.platformContext.setClipboardContent(clipboardInput)
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy")
                    }

                    FilledTonalButton(
                        onClick = {
                            readClipboardValue = container.platformContext.getClipboardContent() ?: "[Empty]"
                        }
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Read")
                    }

                    FilledTonalButton(
                        onClick = {
                            container.platformContext.shareText(clipboardInput)
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share")
                    }
                }

                if (readClipboardValue.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Read Value: $readClipboardValue",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 5. Device Controls Card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Vibration, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Device Actions & Haptics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { container.platformContext.triggerHaptic(120L) }
                    ) {
                        Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Haptic Pulse")
                    }

                    FilledTonalButton(
                        onClick = {
                            container.platformContext.setTorch(true)
                        }
                    ) {
                        Icon(Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Torch On")
                    }

                    OutlinedButton(
                        onClick = {
                            container.platformContext.setTorch(false)
                        }
                    ) {
                        Text("Torch Off")
                    }
                }
            }
        }
    }
}

/**
 * Why a capability is listed but not usable. A capability is never hidden for being unavailable —
 * it is listed with the reason it cannot be used right now.
 */
private fun blockedReason(entry: CapabilityEntry): String = when {
    !entry.masterEnabled -> "Switched off by the master control"
    !entry.isSwitchedOn -> "Switched off"
    !entry.isRegistered -> "No provider registered on this device"
    entry.status is CapabilityStatus.PermissionRequired ->
        "Permission required: " +
            (entry.status as CapabilityStatus.PermissionRequired).missingPermissions.joinToString()
    entry.status is CapabilityStatus.Unavailable ->
        (entry.status as CapabilityStatus.Unavailable).reason
    else -> "Available"
}

@Composable
fun StatusPill(status: CapabilityStatus) {
    val (text, color) = when (status) {
        is CapabilityStatus.Available -> "Available" to MaterialTheme.colorScheme.primary
        is CapabilityStatus.PermissionRequired -> "Permission Required" to MaterialTheme.colorScheme.error
        is CapabilityStatus.DisabledByUser -> "Disabled" to MaterialTheme.colorScheme.outline
        is CapabilityStatus.Unavailable -> "Unavailable" to MaterialTheme.colorScheme.outline
    }

    Badge(containerColor = color) {
        Text(text)
    }
}
