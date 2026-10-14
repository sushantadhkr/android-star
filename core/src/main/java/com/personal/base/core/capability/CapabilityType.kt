package com.personal.base.core.capability

import android.os.Build

/**
 * Enumeration of all system and platform capabilities managed by the base platform.
 */
enum class CapabilityType(
    val displayName: String,
    val description: String,
    val requiredPermissions: List<String> = emptyList(),
    /**
     * What this capability is for.
     *
     * Deliberately has no default: a new capability that forgets to declare where it belongs is a
     * compile error, not a section that quietly never appears. The console groups by this, so a
     * hardcoded table in a front end would be one more thing to forget to update.
     */
    val group: CapabilityGroup
) {
    // Core Sensing & Telemetry
    LOCATION(
        displayName = "Location Services",
        description = "Fine and coarse geographic positioning and provider telemetry",
        requiredPermissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ),
        group = CapabilityGroup.DATA
    ),
    SENSORS(
        displayName = "Hardware Sensors",
        description = "Accelerometer, gyroscope, ambient light, barometer, and orientation",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DATA
    ),
    NOTIFICATIONS(
        displayName = "System Notifications",
        description = "High-priority, status, and categorized channel notifications",
        requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(android.Manifest.permission.POST_NOTIFICATIONS)
        } else emptyList(),
        group = CapabilityGroup.DISPLAY
    ),
    CLIPBOARD(
        displayName = "Clipboard Manager",
        description = "Secure clipboard read and write with privacy awareness",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DATA
    ),
    SHARING(
        displayName = "Share & System Sheets",
        description = "Outbound Android share sheets and inbound share target ingestion",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DATA
    ),
    DEVICE_ACTIONS(
        displayName = "Device Controls & Haptics",
        description = "Haptics, flashlight/torch, battery telemetry, and power metrics",
        requiredPermissions = listOf(android.Manifest.permission.VIBRATE),
        group = CapabilityGroup.DEVICE
    ),
    CAMERA(
        displayName = "Camera & Vision",
        description = "Camera hardware querying, preview and capture triggers",
        requiredPermissions = listOf(android.Manifest.permission.CAMERA),
        group = CapabilityGroup.CAPTURE
    ),
    MICROPHONE(
        displayName = "Microphone & Audio Capture",
        description = "Audio recording capabilities and sensor analysis",
        requiredPermissions = listOf(android.Manifest.permission.RECORD_AUDIO),
        group = CapabilityGroup.CAPTURE
    ),
    BACKGROUND_WORK(
        displayName = "Background Execution",
        description = "Lifecycle-aware WorkManager jobs and managed foreground services",
        requiredPermissions = listOf(
            android.Manifest.permission.FOREGROUND_SERVICE
        ),
        group = CapabilityGroup.DATA
    ),
    CONNECTIVITY(
        displayName = "Network & Wi-Fi Telemetry",
        description = "Live Wi-Fi IP, MAC/Interface, link speed, frequency, and gateway telemetry",
        requiredPermissions = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.ACCESS_WIFI_STATE
        ),
        group = CapabilityGroup.CONNECTIVITY
    ),
    STORAGE(
        displayName = "Storage Access",
        description = "Filesystem, media access, and local secure storage",
        requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        },
        group = CapabilityGroup.DATA
    ),
    CALL_LOG(
        displayName = "Call Logs",
        description = "Read and query telephony call history and statistics",
        requiredPermissions = listOf(android.Manifest.permission.READ_CALL_LOG),
        group = CapabilityGroup.COMMUNICATION
    ),
    CONTACTS_ACCOUNTS(
        displayName = "Contacts & Accounts",
        description = "Address book contacts querying and system accounts",
        requiredPermissions = listOf(
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.GET_ACCOUNTS
        ),
        group = CapabilityGroup.COMMUNICATION
    ),
    NEARBY_DEVICES(
        displayName = "Nearby Devices & Bluetooth",
        description = "Bluetooth scanning, connections, and nearby Wi-Fi devices",
        requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(android.Manifest.permission.BLUETOOTH)
        },
        group = CapabilityGroup.CONNECTIVITY
    ),
    PHONE(
        displayName = "Phone & Telephony",
        description = "Telephony state, SIM info, and network operator metrics",
        requiredPermissions = listOf(android.Manifest.permission.READ_PHONE_STATE),
        group = CapabilityGroup.COMMUNICATION
    ),
    SMS(
        displayName = "SMS & Messaging",
        description = "Read and inspect SMS inbox and messages",
        requiredPermissions = listOf(android.Manifest.permission.READ_SMS),
        group = CapabilityGroup.COMMUNICATION
    ),
    BIOMETRIC(
        displayName = "Biometrics & Fingerprint",
        description = "Hardware biometric locking, fingerprint authentication, and security keyguard",
        requiredPermissions = listOf(android.Manifest.permission.USE_BIOMETRIC),
        group = CapabilityGroup.DEVICE
    ),

    // 44 Standardized Base Capability Providers
    WIFI(
        displayName = "Wi-Fi & Wi-Fi Direct",
        description = "Scan, connect, Wi-Fi Direct (P2P), and soft AP hotspot status",
        requiredPermissions = listOf(
            android.Manifest.permission.ACCESS_WIFI_STATE,
            android.Manifest.permission.CHANGE_WIFI_STATE
        ),
        group = CapabilityGroup.CONNECTIVITY
    ),
    NFC(
        displayName = "NFC Tag & Tap-to-Pair",
        description = "NFC tag reading/writing, beam and tap-to-pair protocols",
        requiredPermissions = listOf(android.Manifest.permission.NFC),
        group = CapabilityGroup.CONNECTIVITY
    ),
    CALENDAR(
        displayName = "Calendar Events",
        description = "Read, query, create, and manage calendar events",
        requiredPermissions = listOf(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR
        ),
        group = CapabilityGroup.DATA
    ),
    BATTERY_POWER(
        displayName = "Battery & Power Management",
        description = "Battery level, health, charging state, thermal status, and power-save modes",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DATA
    ),
    DISPLAY(
        displayName = "Display & Screen",
        description = "Brightness, screen orientation, refresh rate, and display metrics",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DISPLAY
    ),
    AUDIO_VOLUME(
        displayName = "Audio Volume Streams",
        description = "Manage volume streams (Media, Ring, Voice, Alarm) and ringer modes",
        requiredPermissions = listOf(android.Manifest.permission.MODIFY_AUDIO_SETTINGS),
        group = CapabilityGroup.CAPTURE
    ),
    MEDIA_SESSION(
        displayName = "Media Playback Sessions",
        description = "Inspect now-playing metadata, track changes, and remote transport controls",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CAPTURE
    ),
    MEDIA_LIBRARY(
        displayName = "Media Library & MediaStore",
        description = "Query photos, videos, and music collections via MediaStore",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CAPTURE
    ),
    TEXT_TO_SPEECH(
        displayName = "Text-to-Speech (TTS)",
        description = "On-device speech synthesis, custom voices, and audio utterance dispatch",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DATA
    ),
    SPEECH_RECOGNITION(
        displayName = "Speech Recognition",
        description = "On-device voice-to-text conversion and dictation listeners",
        requiredPermissions = listOf(android.Manifest.permission.RECORD_AUDIO),
        group = CapabilityGroup.DATA
    ),
    VIBRATION_HAPTICS(
        displayName = "Vibration & Haptic Feedback",
        description = "Custom vibration waveforms, click patterns, and rich haptic effects",
        requiredPermissions = listOf(android.Manifest.permission.VIBRATE),
        group = CapabilityGroup.DEVICE
    ),
    FLASHLIGHT(
        displayName = "Flashlight / Torch",
        description = "Camera LED torch control and brightness strobe patterns",
        requiredPermissions = listOf(android.Manifest.permission.CAMERA),
        group = CapabilityGroup.DISPLAY
    ),
    INSTALLED_APPS(
        displayName = "Installed Applications",
        description = "List installed packages, query launch intents, and usage statistics",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    ACCESSIBILITY(
        displayName = "Accessibility Automation",
        description = "Accessibility service bridge, UI node inspection, and gesture injection",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    SCREEN_CAPTURE(
        displayName = "Screen Capture & Cast",
        description = "MediaProjection virtual displays, screenshots, and live screen casting",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CAPTURE
    ),
    USB(
        displayName = "USB Host & Accessory",
        description = "Discover USB accessories, OTG peripherals, and manage serial connections",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CONNECTIVITY
    ),
    DO_NOT_DISTURB(
        displayName = "Do Not Disturb & Zen Mode",
        description = "Interruption filter control (All, Priority, Alarms, Total Silence)",
        requiredPermissions = listOf(android.Manifest.permission.ACCESS_NOTIFICATION_POLICY),
        group = CapabilityGroup.DISPLAY
    ),
    SYSTEM_SETTINGS(
        displayName = "System Settings",
        description = "Read and write system settings, secure values, and device configurations",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    ALARM_TIMER(
        displayName = "Exact Alarms & Timers",
        description = "Schedule exact alarms, timers, and wake-up notifications",
        requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(android.Manifest.permission.SCHEDULE_EXACT_ALARM)
        } else emptyList(),
        group = CapabilityGroup.COMMUNICATION
    ),
    QR_BARCODE(
        displayName = "QR & Barcode Utilities",
        description = "Generate and parse QR codes and 1D/2D barcodes",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CAPTURE
    ),
    DEVICE_INFO(
        displayName = "Hardware & Device Telemetry",
        description = "Model, SOC, CPU core usage, RAM metrics, and thermal state",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    HEALTH_CONNECT(
        displayName = "Health & Step Tracking",
        description = "Step counter sensor and Health Connect telemetry bridge",
        requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(android.Manifest.permission.ACTIVITY_RECOGNITION)
        } else emptyList(),
        group = CapabilityGroup.DATA
    ),
    DEVICE_ADMIN(
        displayName = "Device Policy Administration",
        description = "Device admin policies, screen locking, password complexity, and wipe",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    DOWNLOAD_MANAGER(
        displayName = "Download Manager",
        description = "Queue, query, track, and manage background system downloads",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CONNECTIVITY
    ),
    VPN(
        displayName = "VPN Service Tunnel",
        description = "VpnService virtual network interface for secure peer tunnels",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CONNECTIVITY
    ),
    GEOFENCE_ACTIVITY(
        displayName = "Geofencing & Activity Recognition",
        description = "Geographic fence boundaries and user activity recognition transitions",
        requiredPermissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ),
        group = CapabilityGroup.DATA
    ),
    NETWORK_USAGE(
        displayName = "Network Data Usage",
        description = "Historical and real-time mobile and Wi-Fi data usage per application",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CONNECTIVITY
    ),
    SIM_SUBSCRIPTION(
        displayName = "SIM & Subscription Telemetry",
        description = "Dual-SIM info, carrier network, MNC/MCC, and cellular signal strength",
        requiredPermissions = listOf(android.Manifest.permission.READ_PHONE_STATE),
        group = CapabilityGroup.COMMUNICATION
    ),
    REMOTE_INPUT(
        displayName = "Remote Input Injection",
        description = "Keyboard text injection, mouse pointer movement, and gamepad simulation",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    KEYGUARD(
        displayName = "Keyguard & Wallpaper",
        description = "Lock screen state, unlock events, and wallpaper management",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    CALL_CONTROL(
        displayName = "Call Control",
        description = "Telecom framework integration: place, answer, reject, and mute calls",
        requiredPermissions = listOf(
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_PHONE_STATE
        ),
        group = CapabilityGroup.COMMUNICATION
    ),
    NOTIFICATION_LISTENER(
        displayName = "Notification Listener Companion",
        description = "Mirror, filter, inspect, and dismiss notifications from other apps",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DISPLAY
    ),
    OVERLAY_WINDOW(
        displayName = "Floating Overlay Window",
        description = "Draw-over-apps floating HUD, remote pointer, and overlay widgets",
        requiredPermissions = listOf(android.Manifest.permission.SYSTEM_ALERT_WINDOW),
        group = CapabilityGroup.DISPLAY
    ),
    MEDIA_STREAM_ENCODER(
        displayName = "Hardware Stream Encoder",
        description = "Hardware MediaCodec AVC/H.264 & AAC streaming pipelines",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CAPTURE
    ),
    AUDIO_STREAM(
        displayName = "Live Audio Streaming",
        description = "Low-latency raw PCM microphone capture and playback streaming",
        requiredPermissions = listOf(android.Manifest.permission.RECORD_AUDIO),
        group = CapabilityGroup.CAPTURE
    ),
    PACKAGE_INSTALLER(
        displayName = "Package Installer & Manager",
        description = "PackageInstaller session creation, APK installation, and uninstallation",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    DOCUMENTS_PROVIDER(
        displayName = "Documents Provider (SAF)",
        description = "Expose platform storage to external apps via Storage Access Framework",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    CREDENTIAL_MANAGER(
        displayName = "Credential Manager & Passkeys",
        description = "Store and retrieve passkeys, WebAuthn credentials, and secure logins",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    ON_DEVICE_ML(
        displayName = "On-Device Machine Learning",
        description = "On-device OCR, text recognition, image classification, and face detection",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DATA
    ),
    RESTRICTED_SHELL(
        displayName = "Restricted Command Shell",
        description = "Execute allowlisted shell diagnostics (ping, ip, getprop) with user consent",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    SFTP_SERVER(
        displayName = "Embedded SFTP/SSH Server",
        description = "Secure SFTP/SSH daemon for mounting phone filesystem on desktop systems",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CONNECTIVITY
    ),
    ALL_FILES_ACCESS(
        displayName = "All Files Access",
        description = "Full filesystem access (MANAGE_EXTERNAL_STORAGE) management",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CONNECTIVITY
    ),
    INPUT_METHOD(
        displayName = "Remote Input Method (IME)",
        description = "Remote software keyboard service for typing into any Android application",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DEVICE
    ),
    CLIPBOARD_BACKGROUND(
        displayName = "Background Clipboard Helper",
        description = "Foreground-assisted background clipboard synchronization for Android 10+",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.DATA
    ),
    FILE_SERVER(
        displayName = "Embedded File Server",
        description = "Embedded HTTP static and media file server for local peer access",
        requiredPermissions = emptyList(),
        group = CapabilityGroup.CONNECTIVITY
    ),
    NEARBY_CHAT(
        displayName = "Nearby P2P Chat",
        description = "Cryptographically signed peer-to-peer local network messaging",
        requiredPermissions = emptyList(),

        group = CapabilityGroup.CONNECTIVITY
   )
}
