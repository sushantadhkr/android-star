package com.personal.base.di

import android.content.Context
import com.personal.base.core.app.AppManager
import com.personal.base.core.app.AppToggleStore
import com.personal.base.core.app.DashboardComposer
import com.personal.base.core.app.DashboardLayoutStore
import com.personal.base.generated.GeneratedAppRegistry
import com.personal.base.core.capability.CapabilityActions
import com.personal.base.core.capability.CapabilityApi
import com.personal.base.core.capability.CapabilityCatalogue
import com.personal.base.core.capability.CapabilityGate
import com.personal.base.core.security.ApiTokenStore
import com.personal.base.core.capability.PlatformCapabilityRegistry
import com.personal.base.core.command.CommandValidator
import com.personal.base.core.config.ConfigRepository
import com.personal.base.core.config.SharedPrefsConfigRepository
import com.personal.base.core.security.DeviceLockGate
import com.personal.base.core.security.IdleLockPolicy
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.AuditTrail
import com.personal.base.core.platform.Authentication
import com.personal.base.core.platform.Authorization
import com.personal.base.core.platform.AutomationEngine
import com.personal.base.core.platform.CapabilityNegotiation
import com.personal.base.core.platform.ClockSkewHandler
import com.personal.base.core.platform.CommandRouter
import com.personal.base.core.platform.ConsentManager
import com.personal.base.core.platform.DataRedactor
import com.personal.base.core.platform.EmbeddedServer
import com.personal.base.core.platform.EventBus
import com.personal.base.core.platform.GroupBroadcast
import com.personal.base.core.platform.OutboxQueue
import com.personal.base.core.platform.PerDevicePluginConfig
import com.personal.base.core.platform.PluginSandbox
import com.personal.base.core.platform.ProtocolLayer
import com.personal.base.core.platform.RateLimiter
import com.personal.base.core.platform.RawSocketTransport
import com.personal.base.core.platform.RemoteEventSubscription
import com.personal.base.core.platform.ReplayProtection
import com.personal.base.core.platform.SecretVault
import com.personal.base.core.platform.ServerRequest
import com.personal.base.core.platform.ServerResponse
import com.personal.base.core.platform.SyncEngine
import com.personal.base.core.platform.TlsCertManager
import com.personal.base.core.platform.TrustRevocation
import com.personal.base.core.plugin.PlatformContext
import com.personal.base.core.plugin.PluginManager
import com.personal.base.core.runtime.BackupRestore
import com.personal.base.core.runtime.BuildAndTestInfra
import com.personal.base.core.runtime.CacheLayer
import com.personal.base.core.runtime.CrashHandler
import com.personal.base.core.runtime.DataStoreSettings
import com.personal.base.core.runtime.DefaultDispatcherProvider
import com.personal.base.core.runtime.DiagnosticsBundle
import com.personal.base.core.runtime.DispatcherProvider
import com.personal.base.core.runtime.EncryptedStorage
import com.personal.base.core.runtime.EntryPoints
import com.personal.base.core.runtime.ExactScheduler
import com.personal.base.core.runtime.FeatureFlags
import com.personal.base.core.runtime.FgsTimeoutHandler
import com.personal.base.core.runtime.FileProviderConfig
import com.personal.base.core.runtime.IntegrityCheck
import com.personal.base.core.runtime.LocalDatabase
import com.personal.base.core.runtime.LogPersistence
import com.personal.base.core.runtime.MetricsHealthEndpoint
import com.personal.base.core.runtime.NetworkMonitor
import com.personal.base.core.runtime.NetworkSecurityConfig
import com.personal.base.core.runtime.NotificationChannelManager
import com.personal.base.core.runtime.OemKeepAlive
import com.personal.base.core.runtime.OtaUpdater
import com.personal.base.core.runtime.PerformanceMonitor
import com.personal.base.core.runtime.ProcessDeathRestore
import com.personal.base.core.runtime.PushMessaging
import com.personal.base.core.runtime.RetentionCleanup
import com.personal.base.core.runtime.ServiceTypeManager
import com.personal.base.core.runtime.ServiceWatchdog
import com.personal.base.core.runtime.ShareTargetIntake
import com.personal.base.core.runtime.WakeLockManager
import com.personal.base.core.security.DeviceSigner
import com.personal.base.platform.PlatformContextImpl
import com.personal.base.platform.background.BackgroundExecutionManager
import com.personal.base.platform.capabilities.AccessibilityCapabilityProvider
import com.personal.base.platform.capabilities.AlarmTimerCapabilityProvider
import com.personal.base.platform.capabilities.AllFilesAccessCapabilityProvider
import com.personal.base.platform.capabilities.AudioStreamCapabilityProvider
import com.personal.base.platform.capabilities.AudioVolumeCapabilityProvider
import com.personal.base.platform.capabilities.BatteryPowerCapabilityProvider
import com.personal.base.platform.capabilities.BiometricAuthCapabilityProvider
import com.personal.base.platform.capabilities.BluetoothCapabilityProvider
import com.personal.base.platform.capabilities.CalendarCapabilityProvider
import com.personal.base.platform.capabilities.CameraMicCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardBackgroundCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardCapabilityProvider
import com.personal.base.platform.capabilities.ConnectivityCapabilityProvider
import com.personal.base.platform.capabilities.FileServerCapabilityProvider
import com.personal.base.platform.capabilities.NearbyChatCapabilityProvider
import com.personal.base.platform.capabilities.ContactsAccountsCapabilityProvider
import com.personal.base.platform.capabilities.CredentialManagerCapabilityProvider
import com.personal.base.platform.capabilities.DeviceActionCapabilityProvider
import com.personal.base.platform.capabilities.DeviceAdminCapabilityProvider
import com.personal.base.platform.capabilities.DeviceInfoCapabilityProvider
import com.personal.base.platform.capabilities.DisplayCapabilityProvider
import com.personal.base.platform.capabilities.DoNotDisturbCapabilityProvider
import com.personal.base.platform.capabilities.DocumentsProviderCapabilityProvider
import com.personal.base.platform.capabilities.DownloadManagerCapabilityProvider
import com.personal.base.platform.capabilities.FlashlightCapabilityProvider
import com.personal.base.platform.capabilities.GeofenceActivityCapabilityProvider
import com.personal.base.platform.capabilities.HealthConnectCapabilityProvider
import com.personal.base.platform.capabilities.InputMethodCapabilityProvider
import com.personal.base.platform.capabilities.InstalledAppsCapabilityProvider
import com.personal.base.platform.capabilities.KeyguardCapabilityProvider
import com.personal.base.platform.capabilities.LocationCapabilityProvider
import com.personal.base.platform.capabilities.MediaDownloadCapabilityProvider
import com.personal.base.platform.capabilities.MediaLibraryCapabilityProvider
import com.personal.base.platform.capabilities.MediaSessionCapabilityProvider
import com.personal.base.platform.capabilities.MediaStreamEncoderCapabilityProvider
import com.personal.base.platform.capabilities.NetworkUsageCapabilityProvider
import com.personal.base.platform.capabilities.NfcCapabilityProvider
import com.personal.base.core.capability.NotificationActionLog
import com.personal.base.core.platform.DownloadLocationStore
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.receiver.NotificationActionReceiver
import com.personal.base.platform.capabilities.NotificationListenerCapabilityProvider
import com.personal.base.platform.capabilities.OnDeviceMlCapabilityProvider
import com.personal.base.platform.capabilities.OverlayWindowCapabilityProvider
import com.personal.base.platform.capabilities.PackageInstallerCapabilityProvider
import com.personal.base.platform.capabilities.QrBarcodeCapabilityProvider
import com.personal.base.platform.capabilities.RemoteInputCapabilityProvider
import com.personal.base.platform.capabilities.RestrictedShellCapabilityProvider
import com.personal.base.platform.capabilities.SensorsCapabilityProvider
import com.personal.base.platform.capabilities.SharingCapabilityProvider
import com.personal.base.platform.capabilities.CallControlCapabilityProvider
import com.personal.base.platform.capabilities.ScreenCaptureCapabilityProvider
import com.personal.base.platform.capabilities.SimSubscriptionCapabilityProvider
import com.personal.base.platform.capabilities.SmsCapabilityProvider
import com.personal.base.platform.capabilities.SpeechRecognitionCapabilityProvider
import com.personal.base.platform.capabilities.StorageCapabilityProvider
import com.personal.base.platform.capabilities.SystemSettingsCapabilityProvider
import com.personal.base.platform.capabilities.TelephonyCallLogCapabilityProvider
import com.personal.base.platform.capabilities.TextToSpeechCapabilityProvider
import com.personal.base.platform.capabilities.UsbCapabilityProvider
import com.personal.base.platform.capabilities.VibrationHapticsCapabilityProvider
import com.personal.base.platform.capabilities.VpnCapabilityProvider
import com.personal.base.platform.capabilities.WifiCapabilityProvider
import com.personal.base.platform.permissions.PlatformPermissionManager

/**
 * Dependency Injection container assembling all 109 Base Components,
 * coordinating Core Platform Capabilities, Runtime & Infrastructure, and Capability Providers.
 */
class PlatformContainer(val context: Context) {

    // 1. Logging & Security
    val logger = PlatformLogger()
    val deviceSigner = DeviceSigner(context, logger)

    // 2. Configuration & Permissions
    val configRepository: ConfigRepository = SharedPrefsConfigRepository(context)
    val permissionManager = PlatformPermissionManager(context, logger)

    // 3. Central Capability Registry
    val capabilityRegistry = PlatformCapabilityRegistry()
    // Pinned Platform surface: master and per-capability control.
    val capabilityGate = CapabilityGate(context)
    val capabilityCatalogue = CapabilityCatalogue(capabilityRegistry, capabilityGate)
    val apiTokenStore = ApiTokenStore(context)
    val notificationActionLog = NotificationActionLog()
    val downloadLocation = DownloadLocationStore(context)
    val capabilityActions = CapabilityActions(capabilityRegistry, logger, notificationActionLog)
    val capabilityApi = CapabilityApi(
        registry = capabilityRegistry,
        gate = capabilityGate,
        catalogue = capabilityCatalogue,
        tokenStore = apiTokenStore,
        actions = capabilityActions,
        logger = logger,
        appRoutes = { appManager.publishedRoutes() }
    )
    val commandValidator = CommandValidator(configRepository, logger)

    // 4. Platform Facade
    val platformContext: PlatformContext = PlatformContextImpl(capabilityRegistry, logger, context)

    // 5. Plugin & App Managers (Empty by default, awaiting consumer apps)
    val pluginManager = PluginManager(
        platformContext = platformContext,
        capabilityRegistry = capabilityRegistry,
        configRepository = configRepository,
        commandValidator = commandValidator,
        logger = logger
    )
    val appToggleStore = AppToggleStore(context)

    // Declared before the App manager: scopes created there post to each App's own channel.
    val notificationChannelManager = NotificationChannelManager(context, logger)
    val dashboardLayoutStore = DashboardLayoutStore(context)
    val dashboardComposer = DashboardComposer(dashboardLayoutStore)
    val appManager = AppManager(
        platformContext = platformContext,
        toggleStore = appToggleStore,
        logger = logger,
        channelManager = notificationChannelManager
    )

    // 6. C. Runtime and Infrastructure (32 components)
    val localDatabase = LocalDatabase(context, logger)
    val dataStoreSettings = DataStoreSettings(context, logger)
    val crashHandler = CrashHandler(context, logger)
    val logPersistence = LogPersistence(context, logger)
    val wakeLockManager = WakeLockManager(context, logger)
    val networkMonitor = NetworkMonitor(context, logger)
    val serviceWatchdog = ServiceWatchdog(logger)
    val pushMessaging = PushMessaging(logger)
    val otaUpdater = OtaUpdater(context, logger)
    val serviceTypeManager = ServiceTypeManager(logger)
    val exactScheduler = ExactScheduler(context, logger)
    val metricsHealthEndpoint = MetricsHealthEndpoint(context, logger)
    val featureFlags = FeatureFlags(logger)
    val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider()
    val cacheLayer = CacheLayer(context, logger)
    val backupRestore = BackupRestore(context, dataStoreSettings, localDatabase, logger)
    val entryPoints = EntryPoints(context, logger)
    val buildAndTestInfra = BuildAndTestInfra(context, logger)
    val encryptedStorage = EncryptedStorage(context, logger)
    val oemKeepAlive = OemKeepAlive(context, logger)
    val processDeathRestore = ProcessDeathRestore(logger)
    val performanceMonitor = PerformanceMonitor(logger)
    val networkSecurityConfig = NetworkSecurityConfig(context, logger)
    val diagnosticsBundle = DiagnosticsBundle(context, logPersistence, metricsHealthEndpoint, backupRestore, logger)
    val fileProviderConfig = FileProviderConfig(context, logger)
    val integrityCheck = IntegrityCheck(context, logger)
    val fgsTimeoutHandler = FgsTimeoutHandler(logger)
    val retentionCleanup = RetentionCleanup(context, logger)
    val shareTargetIntake = ShareTargetIntake(logger)

    // 7. B. Core Platform Capabilities (33 components)
    val eventBus = EventBus(logger)

    val embeddedServer = EmbeddedServer(
        logger = logger,
        // The server can post notifications, run hardware and change what the platform may do, so
        // every request must present the token. Enforced here so no route can ship unauthenticated.
        authorizer = capabilityApi::authorize,
        // The console is the one exemption. It is a static page with no secrets in it, and a browser
        // cannot attach the header on a plain navigation, so demanding one would mean the page could
        // never load and could never show the form that asks for it.
        publicPaths = CONSOLE_PATHS
    )
    val authentication = Authentication(logger)
    val authorization = Authorization(logger)
    val tlsCertManager = TlsCertManager(logger)
    val secretVault = SecretVault(logger)
    val commandRouter = CommandRouter(logger)
    val capabilityNegotiation = CapabilityNegotiation(logger)
    val rateLimiter = RateLimiter(logger)
    val auditTrail = AuditTrail(localDatabase, logger)
    val outboxQueue = OutboxQueue(logger)
    val syncEngine = SyncEngine(logger)
    val protocolLayer = ProtocolLayer(logger)
    val automationEngine = AutomationEngine(eventBus, logger)
    val pluginSandbox = PluginSandbox(logger)
    val replayProtection = ReplayProtection(logger)
    val consentManager = ConsentManager(logger)
    val trustRevocation = TrustRevocation(logger)
    val remoteEventSubscription = RemoteEventSubscription(logger)
    val clockSkewHandler = ClockSkewHandler(logger)
    val dataRedactor = DataRedactor()
    val groupBroadcast = GroupBroadcast(logger)
    val rawSocketTransport = RawSocketTransport(tlsCertManager, logger)
    val perDevicePluginConfig = PerDevicePluginConfig(logger)

    // 9. Background Execution Manager
    val backgroundManager = BackgroundExecutionManager(
        context = context,
        configRepository = configRepository,
        logger = logger
    )

    // 10. A. Capability Providers (44 components)
    val biometricProvider = BiometricAuthCapabilityProvider(context, logger)
    // Pinned Platform surface: device lock is not an App and no App toggle can disable it.
    val idleLockPolicy = IdleLockPolicy(logger)
    val deviceLockGate = DeviceLockGate(context, idleLockPolicy, logger)
    val accessibilityProvider = AccessibilityCapabilityProvider(context, logger)
    val remoteInputProvider = RemoteInputCapabilityProvider(context, accessibilityProvider, logger)

    fun initialize() {
        logger.i("PlatformContainer", "Bootstrapping Android Star Base Platform (Device: ${deviceSigner.identity.deviceId})...")

        // Core builds the intents for notification buttons but cannot name a class in the shell.
        // Telling it here is what lets a progress notification carry working Cancel and Retry.
        com.personal.base.core.platform.ProgressNotifier.RECEIVER_CLASS =
            NotificationActionReceiver::class.java.name

        // Install Crash Interceptor
        crashHandler.install()

        // Start Monitors & Services
        networkMonitor.startMonitoring()
        automationEngine.start()
        serviceWatchdog.start()

        // The platform's network surface. Routes are registered eagerly but the socket is only
        // opened by the always-on host, so nothing is reachable while that host is stopped.
        capabilityApi.register(embeddedServer)
        registerConsole()

        // Register every declared App. The registry is generated at build time from
        // @PlatformAppDefinition, so this line never changes when Apps are added or removed.
        appManager.registerAll(GeneratedAppRegistry.registrations)

        // Give each App its own notification channel under the Apps group, and drop channels for
        // Apps that no longer exist. Platform channels live in their own group and are untouched.
        notificationChannelManager.syncApps(
            appManager.apps.value.map { it.id to it.name }
        )

        // Apps that implement AppServer publish their own routes. The App manager handles that
        // whenever an App starts, so nothing needs wiring here.
        appManager.apps.value
            .filter { it.instance is com.personal.base.core.app.AppServer }
            .forEach { entry ->
                logger.i("PlatformContainer", "App '${entry.name}' is ready to serve")
            }

        logger.i("PlatformContainer", "Registered ${appManager.apps.value.size} app(s).")

        // Register All 44 Base Capability Providers
        capabilityRegistry.register(biometricProvider)
        capabilityRegistry.register(WifiCapabilityProvider(context, logger))
        capabilityRegistry.register(NfcCapabilityProvider(context, logger))
        capabilityRegistry.register(CalendarCapabilityProvider(context, logger))
        capabilityRegistry.register(BatteryPowerCapabilityProvider(context, logger))
        capabilityRegistry.register(DisplayCapabilityProvider(context, logger))
        capabilityRegistry.register(AudioVolumeCapabilityProvider(context, logger))
        capabilityRegistry.register(MediaSessionCapabilityProvider(context, logger))
        capabilityRegistry.register(MediaLibraryCapabilityProvider(context, logger))
        capabilityRegistry.register(TextToSpeechCapabilityProvider(context, logger))
        capabilityRegistry.register(SpeechRecognitionCapabilityProvider(context, logger))
        capabilityRegistry.register(VibrationHapticsCapabilityProvider(context, logger))
        capabilityRegistry.register(FlashlightCapabilityProvider(context, logger))
        capabilityRegistry.register(InstalledAppsCapabilityProvider(context, logger))
        capabilityRegistry.register(accessibilityProvider)
        capabilityRegistry.register(ScreenCaptureCapabilityProvider(context, logger))
        capabilityRegistry.register(UsbCapabilityProvider(context, logger))
        capabilityRegistry.register(DoNotDisturbCapabilityProvider(context, logger))
        capabilityRegistry.register(SystemSettingsCapabilityProvider(context, logger))
        capabilityRegistry.register(AlarmTimerCapabilityProvider(context, logger))
        capabilityRegistry.register(QrBarcodeCapabilityProvider(context, logger))
        capabilityRegistry.register(DeviceInfoCapabilityProvider(context, logger))
        capabilityRegistry.register(HealthConnectCapabilityProvider(context, logger))
        capabilityRegistry.register(DeviceAdminCapabilityProvider(context, logger))
        capabilityRegistry.register(DownloadManagerCapabilityProvider(context, logger))
        // Replaces the system DownloadManager for anything the API drives, so the file lands in
        // Downloads through MediaStore and its progress and destination can actually be reported.
        capabilityRegistry.register(MediaDownloadCapabilityProvider(context, logger, downloadLocation))
        capabilityRegistry.register(VpnCapabilityProvider(context, logger))
        capabilityRegistry.register(GeofenceActivityCapabilityProvider(context, logger))
        capabilityRegistry.register(NetworkUsageCapabilityProvider(context, logger))
        capabilityRegistry.register(SimSubscriptionCapabilityProvider(context, logger))
        capabilityRegistry.register(remoteInputProvider)
        capabilityRegistry.register(KeyguardCapabilityProvider(context, logger))
        capabilityRegistry.register(CallControlCapabilityProvider(context, logger))
        capabilityRegistry.register(NotificationListenerCapabilityProvider(context, logger))
        capabilityRegistry.register(OverlayWindowCapabilityProvider(context, logger))
        capabilityRegistry.register(MediaStreamEncoderCapabilityProvider(logger))
        capabilityRegistry.register(AudioStreamCapabilityProvider(context, logger))
        capabilityRegistry.register(PackageInstallerCapabilityProvider(context, logger))
        capabilityRegistry.register(DocumentsProviderCapabilityProvider(context, logger))
        capabilityRegistry.register(CredentialManagerCapabilityProvider(context, logger))
        capabilityRegistry.register(OnDeviceMlCapabilityProvider(context, logger))
        capabilityRegistry.register(RestrictedShellCapabilityProvider(logger))
        capabilityRegistry.register(AllFilesAccessCapabilityProvider(context, logger))
        capabilityRegistry.register(InputMethodCapabilityProvider(logger))
        capabilityRegistry.register(ClipboardBackgroundCapabilityProvider(context, logger))

        // Also register low-level legacy hardware providers
        capabilityRegistry.register(SensorsCapabilityProvider(context, logger))
        capabilityRegistry.register(LocationCapabilityProvider(context, permissionManager, logger))
        capabilityRegistry.register(
            NotificationCapabilityProvider(
                context = context,
                permissionManager = permissionManager,
                logger = logger,
                // Core cannot name a class in the shell, so the shell hands the receiver over.
                actionReceiver = NotificationActionReceiver::class.java.name
            )
        )
        capabilityRegistry.register(ClipboardCapabilityProvider(context, logger))
        capabilityRegistry.register(SharingCapabilityProvider(context, logger))
        capabilityRegistry.register(DeviceActionCapabilityProvider(context, logger))
        capabilityRegistry.register(CameraMicCapabilityProvider(context, permissionManager, isAudio = false, logger))
        capabilityRegistry.register(CameraMicCapabilityProvider(context, permissionManager, isAudio = true, logger))
        val connectivityProvider = ConnectivityCapabilityProvider(context, logger)
        capabilityRegistry.register(connectivityProvider)
        capabilityRegistry.register(BluetoothCapabilityProvider(context, permissionManager, logger))
        capabilityRegistry.register(StorageCapabilityProvider(context, permissionManager, logger))
        capabilityRegistry.register(TelephonyCallLogCapabilityProvider(context, permissionManager, logger))
        capabilityRegistry.register(ContactsAccountsCapabilityProvider(context, permissionManager, logger))
        capabilityRegistry.register(SmsCapabilityProvider(context, permissionManager, logger))

        // These two open real sockets, so they are registered and their actions are advertised.
        //
        // SftpServerCapabilityProvider is deliberately not registered: it sets a boolean and logs
        // "SFTP daemon initialized" without opening anything. Advertising it would tell a caller a
        // server is running when there is nothing to connect to. Until it has a real daemon it
        // stays bare, and a test says so.
        capabilityRegistry.register(FileServerCapabilityProvider(context, connectivityProvider, logger))
        capabilityRegistry.register(NearbyChatCapabilityProvider(context, deviceSigner, connectivityProvider, logger))

        // Initialize background tasks
        backgroundManager.initialize()

        logger.i("PlatformContainer", "Android Star Base Platform initialized with ${capabilityRegistry.getAllStatuses().size} capabilities.")
    }

    fun release() {
        embeddedServer.stop()
        networkMonitor.stopMonitoring()
        capabilityRegistry.releaseAll()
    }

    /**
     * Publishes the capability console on the server.
     *
     * Serving it from the phone is what makes the console work from a browser at all. Opened from
     * disk it would be a `file://` page calling the phone, and the browser would send an OPTIONS
     * preflight for the `Authorization` header that this server never answers. Served from here the
     * page and the API share an origin, so no preflight happens and no CORS configuration is
     * needed or wanted.
     */
    private fun registerConsole() {
        val page = runCatching {
            context.assets.open("index.html").bufferedReader().use { it.readText() }
        }.getOrElse {
            logger.w("PlatformContainer", "Console asset missing; / will not be served: ${it.message}")
            return
        }

        val handler = { _: ServerRequest ->
            ServerResponse(200, "text/html; charset=utf-8", page)
        }

        embeddedServer.registerRoute("/", handler)
        embeddedServer.registerRoute("/index.html", handler)

        logger.i("PlatformContainer", "Capability console served at http://<this device>:${embeddedServer.port}/")
    }

    private companion object {
        /**
         * The console, and nothing else.
         *
         * Matched exactly by [EmbeddedServer], never by prefix, so this cannot widen to `/api/...`
         * by accident. Every endpoint the console drives still requires the token.
         */
        val CONSOLE_PATHS = setOf("/", "/index.html")
    }
}
