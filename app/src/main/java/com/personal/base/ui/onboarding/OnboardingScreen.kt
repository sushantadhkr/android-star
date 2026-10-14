package com.personal.base.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PermDeviceInformation
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class PermissionCategory(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val permissions: List<String>
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }

    val permissionGroups = remember {
        val list = mutableListOf<PermissionCategory>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(
                PermissionCategory(
                    title = "Notifications & Alerts",
                    description = "Foreground service status, background alerts, and incoming notifications",
                    icon = Icons.Default.Notifications,
                    permissions = listOf(android.Manifest.permission.POST_NOTIFICATIONS)
                )
            )
        }

        list.add(
            PermissionCategory(
                title = "Phone & Telephony",
                description = "Carrier network metrics, SIM info, and call control",
                icon = Icons.Default.PermDeviceInformation,
                permissions = listOf(
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.CALL_PHONE
                )
            )
        )

        list.add(
            PermissionCategory(
                title = "Call Logs",
                description = "Read call history and query telephony statistics",
                icon = Icons.Default.Shield,
                permissions = listOf(
                    android.Manifest.permission.READ_CALL_LOG,
                    android.Manifest.permission.WRITE_CALL_LOG
                )
            )
        )

        list.add(
            PermissionCategory(
                title = "SMS & Messaging",
                description = "SMS message inspection, incoming verification, and gateway events",
                icon = Icons.Default.Security,
                permissions = listOf(
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.SEND_SMS
                )
            )
        )

        list.add(
            PermissionCategory(
                title = "Contacts & Accounts",
                description = "Device address book and account sync queries",
                icon = Icons.Default.PermDeviceInformation,
                permissions = listOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.WRITE_CONTACTS,
                    android.Manifest.permission.GET_ACCOUNTS
                )
            )
        )

        list.add(
            PermissionCategory(
                title = "Geographic Location",
                description = "High precision GPS positioning and geofence tracking",
                icon = Icons.Default.PermDeviceInformation,
                permissions = listOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(
                PermissionCategory(
                    title = "Nearby Devices & Bluetooth",
                    description = "Local peer discovery, Bluetooth LE scanning, and P2P pairing",
                    icon = Icons.Default.LockOpen,
                    permissions = listOf(
                        android.Manifest.permission.BLUETOOTH_SCAN,
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.NEARBY_WIFI_DEVICES
                    )
                )
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(
                PermissionCategory(
                    title = "Media & Filesystem",
                    description = "Photos, videos, and music collections for local file server and media library",
                    icon = Icons.Default.Shield,
                    permissions = listOf(
                        android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO,
                        android.Manifest.permission.READ_MEDIA_AUDIO
                    )
                )
            )
        } else {
            list.add(
                PermissionCategory(
                    title = "Storage Access",
                    description = "External storage read/write access",
                    icon = Icons.Default.Shield,
                    permissions = listOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            )
        }

        list.add(
            PermissionCategory(
                title = "Hardware Camera & Audio",
                description = "Camera vision preview and raw microphone capture streaming",
                icon = Icons.Default.Shield,
                permissions = listOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO
                )
            )
        )

        list.add(
            PermissionCategory(
                title = "Calendar Events",
                description = "Schedule and inspect device calendar appointments",
                icon = Icons.Default.PermDeviceInformation,
                permissions = listOf(
                    android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR
                )
            )
        )

        list
    }

    // Collect all permissions into a flat array
    val allRequiredPermissions = remember(permissionGroups) {
        permissionGroups.flatMap { it.permissions }.distinct().toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTrigger++
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        onClick = {
                            permissionLauncher.launch(allRequiredPermissions)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant All Platform Permissions", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            val prefs = context.getSharedPreferences("android_star_onboarding", Context.MODE_PRIVATE)
                            prefs.edit().putBoolean("onboarding_done", true).apply()
                            onComplete()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Continue to Platform Dashboard")
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Welcome to Android Star",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Android Star acts as your autonomous base platform. To control hardware, background services, sensors, and peer gateways, please grant platform capabilities below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Battery Optimization Exemption Card
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isIgnoringBattery) Color(0xFF1B5E20).copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isIgnoringBattery) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                                contentDescription = null,
                                tint = if (isIgnoringBattery) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Battery Optimization",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isIgnoringBattery) "Unrestricted background execution enabled"
                                    else "Required for 24/7 background platform operation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!isIgnoringBattery) {
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Exempt")
                            }
                        }
                    }
                }
            }

            // Category Cards
            items(permissionGroups.size) { index ->
                val group = permissionGroups[index]
                val allGranted = group.permissions.all { perm ->
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    if (allGranted) Color(0xFF2E7D32).copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = group.icon,
                                contentDescription = null,
                                tint = if (allGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = group.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = if (allGranted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (allGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
