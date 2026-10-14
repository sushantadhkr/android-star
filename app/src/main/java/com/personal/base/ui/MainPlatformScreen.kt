package com.personal.base.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.personal.base.di.PlatformContainer
import com.personal.base.core.app.AppScopeImpl
import com.personal.base.ui.apps.AppsScreen
import com.personal.base.ui.capabilities.CapabilitiesScreen
import com.personal.base.ui.dashboard.DashboardScreen
import com.personal.base.ui.logs.LogsScreen
import com.personal.base.ui.navigation.NavigationDestination
import com.personal.base.ui.navigation.OverlayDestination
import com.personal.base.ui.onboarding.OnboardingScreen
import com.personal.base.ui.security.BiometricLockScreen
import com.personal.base.ui.settings.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPlatformScreen(
    container: PlatformContainer,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember(context) {
        context.getSharedPreferences("android_star_onboarding", Context.MODE_PRIVATE)
    }
    var isOnboardingDone by remember {
        mutableStateOf(prefs.getBoolean("onboarding_done", false))
    }

    // 1. Initial Interactive Onboarding Flow for Runtime Permissions
    if (!isOnboardingDone) {
        OnboardingScreen(
            onComplete = {
                isOnboardingDone = true
                container.permissionManager.refreshPermissions()
                container.capabilityRegistry.refreshStatus()
            }
        )
        return
    }

    // 2. Biometric & Device Credential Security Gate
    //    The lock is a pinned Platform surface and sits ahead of every App surface. No App toggle
    //    can bypass it.
    val isAppLocked by container.biometricProvider.isAppLocked.collectAsState()
    val isLockEnabled by container.biometricProvider.isLockEnabled.collectAsState()
    val gateLocked by container.deviceLockGate.mustUnlock.collectAsState()

    // The lock clock only runs while the app is away. While it is on screen the user is present,
    // so an aggressive timeout can never re-lock on top of them straight after unlocking.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> container.deviceLockGate.onForeground()
                Lifecycle.Event.ON_STOP -> container.deviceLockGate.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Evaluate the away policy; it latches once the away window closes.
    if (isLockEnabled) {
        container.deviceLockGate.evaluate()
    }

    if (isAppLocked || gateLocked) {
        BiometricLockScreen(
            container = container,
            activity = activity
        )
        return
    }

    var currentDestination by remember { mutableStateOf(NavigationDestination.DASHBOARD) }
    var overlay by remember { mutableStateOf<OverlayDestination?>(null) }
    var overflowOpen by remember { mutableStateOf(false) }
    val activeAppId by container.appManager.activeAppId.collectAsState()
    val activeApp = activeAppId?.let { container.appManager.instanceOf(it) }

    // Back closes an open overlay instead of leaving the app. Without this the system back button
    // would exit the platform while a sub-page was still open.
    BackHandler(enabled = overlay != null) {
        overlay = null
    }

    // 3. Full-Screen App Renderer (when a running App is launched)
    if (activeApp != null) {
        activeApp!!.AppContent(
            scope = AppScopeImpl(
                container.appManager,
                activeApp!!.id,
                container.platformContext,
                container.notificationChannelManager,
                container.logger
            ),
            onBack = { container.appManager.closeActiveApp() }
        )
        return
    }

    // An overlay is a page in its own right: it takes over the shell, so the page bar is hidden
    // and the header names the page actually being shown.
    val activeOverlay = overlay
    val headerTitle = if (activeOverlay != null) {
        activeOverlay.title
    } else {
        currentDestination.title
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = headerTitle,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    // Only offered when there is somewhere to go back to.
                    if (activeOverlay != null) {
                        IconButton(onClick = { overlay = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isLockEnabled && activeOverlay == null) {
                        IconButton(onClick = { container.biometricProvider.lockNow() }) {
                            Icon(Icons.Default.Lock, contentDescription = "Lock Device")
                        }
                    }
                    // Overflow: platform surfaces reached from within a page rather than the bar.
                    // Hidden on an overlay, since its only entry is the page already open.
                    if (activeOverlay == null) {
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Capabilities") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Extension, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowOpen = false
                                        overlay = OverlayDestination.CAPABILITIES
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            // Hidden while an overlay is open: an overlay is a page of its own, not a tab of the
            // platform shell, so the page bar does not apply to it.
            if (activeOverlay == null) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    NavigationDestination.values().forEach { destination ->
                        NavigationBarItem(
                            selected = currentDestination == destination,
                            onClick = { currentDestination = destination },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.title
                                )
                            },
                            label = {
                                Text(destination.title)
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)

        // An overlay replaces the page beneath it. Rendering both would stack two full screens in
        // the same slot, which is what made the pages appear to overlap.
        val activeOverlay = overlay
        if (activeOverlay != null) {
            when (activeOverlay) {
                OverlayDestination.CAPABILITIES -> CapabilitiesScreen(
                    container = container,
                    onBack = { overlay = null },
                    modifier = contentModifier
                )
            }
        } else {
            when (currentDestination) {
                NavigationDestination.DASHBOARD -> DashboardScreen(
                    container = container,
                    modifier = contentModifier
                )
                NavigationDestination.APPS -> AppsScreen(container = container, modifier = contentModifier)
                NavigationDestination.LOGS -> LogsScreen(container = container, modifier = contentModifier)
                NavigationDestination.SETTINGS -> SettingsScreen(
                    container = container,
                    onOpenCapabilities = { overlay = OverlayDestination.CAPABILITIES },
                    modifier = contentModifier
                )
            }
        }
    }
}
