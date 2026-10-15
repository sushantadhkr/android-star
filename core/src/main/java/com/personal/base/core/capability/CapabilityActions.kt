package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.runBlocking
import com.personal.base.core.platform.NotificationActionContract
import com.personal.base.core.platform.NotificationButton
import com.personal.base.core.platform.NotificationInput
import com.personal.base.core.platform.ServerResponse
import com.personal.base.core.plugin.PlatformLocation
import com.personal.base.platform.capabilities.AudioStreamCapabilityProvider
import com.personal.base.platform.capabilities.AccessibilityCapabilityProvider
import com.personal.base.platform.capabilities.AllFilesAccessCapabilityProvider
import com.personal.base.platform.capabilities.BackgroundWorkCapabilityProvider
import com.personal.base.platform.capabilities.BiometricAuthCapabilityProvider
import com.personal.base.platform.capabilities.BluetoothCapabilityProvider
import com.personal.base.platform.capabilities.BluetoothDeviceInfo
import com.personal.base.platform.capabilities.DownloadManagerCapabilityProvider
import com.personal.base.platform.capabilities.FileServerCapabilityProvider
import com.personal.base.platform.capabilities.GeofenceActivityCapabilityProvider
import com.personal.base.platform.capabilities.HealthConnectCapabilityProvider
import com.personal.base.platform.capabilities.LocationCapabilityProvider
import com.personal.base.platform.capabilities.MediaDownloadCapabilityProvider
import com.personal.base.platform.capabilities.MediaStreamEncoderCapabilityProvider
import com.personal.base.platform.capabilities.MediaSessionCapabilityProvider
import com.personal.base.platform.capabilities.NearbyChatCapabilityProvider
import com.personal.base.platform.capabilities.PeerAuthDecision
import com.personal.base.platform.capabilities.PackageInstallerCapabilityProvider
import com.personal.base.platform.capabilities.PlatformGeofence
import com.personal.base.platform.capabilities.AlarmTimerCapabilityProvider
import com.personal.base.platform.capabilities.AudioVolumeCapabilityProvider
import com.personal.base.platform.capabilities.BatteryPowerCapabilityProvider
import com.personal.base.platform.capabilities.CalendarCapabilityProvider
import com.personal.base.platform.capabilities.CallControlCapabilityProvider
import com.personal.base.platform.capabilities.CameraMicCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardBackgroundCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardRead
import com.personal.base.platform.capabilities.ConnectivityCapabilityProvider
import com.personal.base.platform.capabilities.ContactsAccountsCapabilityProvider
import com.personal.base.platform.capabilities.CredentialManagerCapabilityProvider
import com.personal.base.platform.capabilities.DeviceActionCapabilityProvider
import com.personal.base.platform.capabilities.DeviceAdminCapabilityProvider
import com.personal.base.platform.capabilities.DeviceInfoCapabilityProvider
import com.personal.base.platform.capabilities.DisplayCapabilityProvider
import com.personal.base.platform.capabilities.DocumentsProviderCapabilityProvider
import com.personal.base.platform.capabilities.InputMethodCapabilityProvider
import com.personal.base.platform.capabilities.KeyguardCapabilityProvider
import com.personal.base.platform.capabilities.DoNotDisturbCapabilityProvider
import com.personal.base.platform.capabilities.FlashlightCapabilityProvider
import com.personal.base.platform.capabilities.InstalledAppsCapabilityProvider
import com.personal.base.platform.capabilities.MediaLibraryCapabilityProvider
import com.personal.base.platform.capabilities.NetworkUsageCapabilityProvider
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.platform.capabilities.NfcCapabilityProvider
import com.personal.base.platform.capabilities.NotificationListenerCapabilityProvider
import com.personal.base.platform.capabilities.OnDeviceMlCapabilityProvider
import com.personal.base.platform.capabilities.QrBarcodeCapabilityProvider
import com.personal.base.platform.capabilities.PhoneCapabilityProvider
import com.personal.base.platform.capabilities.TapOutcome
import com.personal.base.platform.capabilities.RemoteInputCapabilityProvider
import com.personal.base.platform.capabilities.RestrictedShellCapabilityProvider
import com.personal.base.platform.capabilities.ScreenCaptureCapabilityProvider
import com.personal.base.platform.capabilities.SharingCapabilityProvider
import com.personal.base.platform.capabilities.SimSubscriptionCapabilityProvider
import com.personal.base.platform.capabilities.SmsCapabilityProvider
import com.personal.base.platform.capabilities.SmsMessage
import com.personal.base.platform.capabilities.SensorsCapabilityProvider
import com.personal.base.platform.capabilities.SpeechRecognitionCapabilityProvider
import com.personal.base.platform.capabilities.StorageCapabilityProvider
import com.personal.base.platform.capabilities.SystemSettingsCapabilityProvider
import com.personal.base.platform.capabilities.TelephonyCallLogCapabilityProvider
import com.personal.base.platform.capabilities.TextToSpeechCapabilityProvider
import com.personal.base.platform.capabilities.UsbCapabilityProvider
import com.personal.base.platform.capabilities.VpnCapabilityProvider
import com.personal.base.platform.capabilities.WifiCapabilityProvider
import com.personal.base.platform.capabilities.OverlayWindowCapabilityProvider
import com.personal.base.platform.permissions.RestrictedPermissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The action surface of the platform, declared as documented [CapabilitySpec]s.
 *
 * Each capability's actions, their documentation and their implementations live in one place, so
 * the catalogue, `/api/capabilities/docs` and the dispatcher can never disagree.
 *
 * A capability whose provider cannot actually do something is not listed with a pretend action —
 * it is either genuinely wired, or it advertises nothing.
 */
class CapabilityActions(
    private val registry: PlatformCapabilityRegistry,
    private val logger: PlatformLogger,
    /** Records notification button presses. Absent only where nothing records them. */
    private val actionLog: NotificationActionLog? = null
 ) {

    private companion object {
        /** A command like `top -n 1` can print thousands of lines; a caller who wants more asks for slices. */
        const val MAX_OUTPUT_LINES = 200

        /** Beyond this a caller is almost certainly not scheduling work, and a long hold is useless. */
        const val MAX_WORK_DELAY_SECONDS = 7 * 24 * 60 * 60L
    }

    /** Every capability with a callable surface. */
    val specs: List<CapabilitySpec> by lazy { buildSpecs() }

    fun specFor(type: CapabilityType): CapabilitySpec? = specs.firstOrNull { it.type == type }

    fun actionsFor(type: CapabilityType): List<String> = specFor(type)?.names ?: emptyList()

    /**
     * Dispatches an action.
     *
     * An unknown action returns the capability's own documentation, so a caller who guessed wrong is
     * told what is actually possible rather than just getting a rejection.
     */
    fun invoke(type: CapabilityType, action: String, payload: JsonObject): ServerResponse {
        val spec = specFor(type)
            ?: return ServerResponse(
                501, "application/json",
                """{"error":"Capability '${type.name}' exposes no actions","docs":"/api/capabilities/docs"}"""
            )

        val target = spec.action(action)
            ?: return unknownAction(type, spec, action)

        return runCatching { target.handler(payload) }
            .getOrElse {
                logger.e("CapabilityActions", "'$action' on ${type.name} failed: ${it.message}")
                ServerResponse(500, "application/json", """{"error":"${it.message}"}""")
            }
    }

    // =====================================================================
    // Declarations
    // =====================================================================

    private fun buildSpecs(): List<CapabilitySpec> = listOf(
        CapabilitySpec(
            CapabilityType.DEVICE_ACTIONS, "Haptics, torch and device controls",
            listOf(
                ActionSpec("haptic", "Vibrate the device", listOf(
                    ParamDoc("durationMs", "integer", false, "How long to buzz", "200")
                ), """{"capability":"DEVICE_ACTIONS","action":"haptic","durationMs":200}""",
                    """{"vibrated":200}""", handler = { p -> deviceHaptic(p) }),
                ActionSpec("torch", "Turn the torch on or off", listOf(
                    ParamDoc("enabled", "boolean", false, "Whether the torch should be on", "true")
                ), """{"capability":"DEVICE_ACTIONS","action":"torch","enabled":true}""",
                    """{"torch":true}""", handler = { p -> deviceTorch(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.VIBRATION_HAPTICS, "Haptic feedback", listOf(
                ActionSpec("pulse", "Vibrate the device", listOf(
                    ParamDoc("durationMs", "integer", false, "How long to buzz", "200")
                ), """{"capability":"VIBRATION_HAPTICS","action":"pulse","durationMs":200}""",
                    """{"vibrated":200}""", handler = { p -> hapticPulse(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.NOTIFICATIONS, "Post a notification to the shade", listOf(
                ActionSpec(
                    "post",
                    "Show a notification, tappable to a link and with inline action buttons",
                    listOf(
                        ParamDoc("title", "string", true, "Notification title", "Hello"),
                        ParamDoc("message", "string", false, "Body text", "from curl"),
                        ParamDoc("priorityHigh", "boolean", false, "Use the high-priority channel", "false"),
                        ParamDoc("link", "string", false,
                            "http/https URL opened when tapped. Omit it to open the app instead.",
                            "https://ci.example.com/build/482"),
                        ParamDoc("buttons", "array", false,
                            "Inline buttons, at most 3: [{id, label, icon?, input?, invoke?}] " +
                                "where icon is one of " + NotificationActionContract.ICONS.joinToString("|") +
                                ", input is {key, hint} for a reply field, and invoke is " +
                                "\"CAPABILITY:action\" (or \"CAPABILITY:action:param\" to send typed text there). " +
                                "Pressing a button is still subject to every capability's switch.",
                            """[{"id":"snooze","label":"Snooze","icon":"alarm","invoke":"ALARM_TIMER:set:minutes"},{"id":"reply","label":"Reply","icon":"reply","input":{"key":"text","hint":"Type a reply"},"invoke":"NOTIFICATIONS:post:text"}]"""
                        )
                    ),
                    """{"capability":"NOTIFICATIONS","action":"post","title":"Build passed","message":"Tap to view","link":"https://ci.example.com/build/482","buttons":[{"id":"done","label":"Mark done","icon":"check","invoke":"CLIPBOARD:write"}]}""",
                    """{"posted":true,"id":4211,"opensLink":true,"buttonsShown":1,"buttonsDropped":[]}""",
                    handler = { p -> notifyPost(p) }
                ),
                ActionSpec(
                    "buttonLog",
                    "Read the notification buttons pressed on this device, newest first",
                    listOf(
                        ParamDoc("limit", "integer", false, "How many presses to return", "10")
                    ),
                    """{"capability":"NOTIFICATIONS","action":"buttonLog","limit":5}""",
                    """{"count":1,"presses":[{"buttonId":"done","label":"Mark done","notificationTitle":"Build passed","typedText":null,"invokeTarget":"CLIPBOARD:write","invokeStatus":200,"at":1752000000000}]}""",
                    handler = { p -> notifyButtonLog(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.SMS, "Read the SMS inbox", listOf(
                ActionSpec("count", "How many messages the inbox holds", emptyList(),
                    """{"capability":"SMS","action":"count"}""",
                    """{"count":214}""", handler = { p -> smsCount(p) }),
                ActionSpec("list", "Read recent inbox messages, newest first", listOf(
                    ParamDoc("limit", "integer", false, "How many messages, 1-200", "20"),
                    ParamDoc("search", "string", false, "Only messages whose body or sender contains this", "invoice")
                ), """{"capability":"SMS","action":"list","limit":3}""",
                    """{"count":3,"messages":[{"id":41,"address":"+441234","body":"Invoice ready","date":1752000000000,"type":"inbox","read":false}]}""",
                    handler = { p -> smsList(p) }),
                ActionSpec("read", "Read one message by its id", listOf(
                    ParamDoc("id", "integer", true, "The message id, as returned by list", "41")
                ), """{"capability":"SMS","action":"read","id":41}""",
                    """{"id":41,"address":"+441234","body":"Invoice ready","date":1752000000000,"type":"inbox","read":false}""",
                    handler = { p -> smsRead(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.ALARM_TIMER, "Set a timer through the system clock app", listOf(
                ActionSpec("set", "Start a countdown timer", listOf(
                    ParamDoc("seconds", "integer", true, "How long the timer runs", "300"),
                    ParamDoc("message", "string", false, "Label shown on the timer", "Timer")
                ), """{"capability":"ALARM_TIMER","action":"set","seconds":300,"message":"Tea"}""",
                    """{"set":true,"seconds":300}""", handler = { p -> alarmSet(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.AUDIO_VOLUME, "Read and change the volume streams", listOf(
                ActionSpec("get", "Current volume of every stream, as a percentage", emptyList(),
                    """{"capability":"AUDIO_VOLUME","action":"get"}""",
                    """{"media":40,"ring":60,"voice":70,"alarm":80,"ringerMode":"normal"}""",
                    handler = { p -> audioVolumeGet(p) }),
                ActionSpec("setMedia", "Set the media volume", listOf(
                    ParamDoc("percent", "integer", true, "Volume 0-100", "40")
                ), """{"capability":"AUDIO_VOLUME","action":"setMedia","percent":40}""",
                    """{"set":true,"percent":40}""", handler = { p -> audioVolumeSet(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CALENDAR, "Read the device calendar", listOf(
                ActionSpec("upcoming", "Upcoming events across all calendars", listOf(
                    ParamDoc("limit", "integer", false, "How many events, 1-100", "10")
                ), """{"capability":"CALENDAR","action":"upcoming","limit":5}""",
                    """{"count":2,"events":[{"id":1,"title":"Standup","start":1752000000000,"end":1752001800000}]}""",
                    handler = { p -> calendarUpcoming(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CALL_CONTROL, "Place a phone call", listOf(
                ActionSpec("place", "Start a call to a number", listOf(
                    ParamDoc("number", "string", true, "The number to call", "+441234567890")
                ), """{"capability":"CALL_CONTROL","action":"place","number":"+441234567890"}""",
                    """{"placing":true}""", handler = { p -> callPlace(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CALL_LOG, "Read the call history", listOf(
                ActionSpec("count", "How many calls are in the history", emptyList(),
                    """{"capability":"CALL_LOG","action":"count"}""",
                    """{"count":128}""", handler = { p -> callLogCount(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CLIPBOARD_BACKGROUND, "Write to the clipboard from the background", listOf(
                ActionSpec("write", "Copy text, which the background host is always allowed to do", listOf(
                    ParamDoc("text", "string", true, "Text to copy", "hello"),
                    ParamDoc("label", "string", false, "Label shown in the clipboard UI", "Android Star")
                ), """{"capability":"CLIPBOARD_BACKGROUND","action":"write","text":"hello"}""",
                    """{"written":true}""", handler = { p -> clipboardBgWrite(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CONTACTS_ACCOUNTS, "Count contacts and device accounts", listOf(
                ActionSpec("contacts", "How many contacts the device holds", emptyList(),
                    """{"capability":"CONTACTS_ACCOUNTS","action":"contacts"}""",
                    """{"contacts":412}""", handler = { p -> contactsCount(p) }),
                ActionSpec("accounts", "How many accounts are on the device", emptyList(),
                    """{"capability":"CONTACTS_ACCOUNTS","action":"accounts"}""",
                    """{"accounts":3}""", handler = { p -> accountsCount(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CREDENTIAL_MANAGER, "Passkey support", listOf(
                ActionSpec("passkeys", "Whether this device and build support passkeys", emptyList(),
                    """{"capability":"CREDENTIAL_MANAGER","action":"passkeys"}""",
                    """{"supported":true}""", handler = { p -> passkeySupport(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.DEVICE_ADMIN, "Device administrator control", listOf(
                ActionSpec("lockScreen", "Lock the screen now", emptyList(),
                    """{"capability":"DEVICE_ADMIN","action":"lockScreen"}""",
                    """{"locked":true}""", handler = { p -> deviceAdminLock(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.DISPLAY, "Read the display state", listOf(
                ActionSpec("get", "Brightness, display state and refresh rate", emptyList(),
                    """{"capability":"DISPLAY","action":"get"}""",
                    """{"brightnessPercent":55,"autoBrightness":true,"displayState":"on","refreshRateHz":60.0}""",
                    handler = { p -> displayGet(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.DO_NOT_DISTURB, "Change the Do Not Disturb filter", listOf(
                ActionSpec("set", "Set which notifications interrupt you", listOf(
                    ParamDoc("filter", "string", true,
                        "One of off, priority, none, alarms", "priority")
                ), """{"capability":"DO_NOT_DISTURB","action":"set","filter":"priority"}""",
                    """{"set":true,"filter":"priority"}""", handler = { p -> dndSet(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.SIM_SUBSCRIPTION, "Read the SIM slots", listOf(
                ActionSpec("slots", "Every SIM slot with its carrier and roaming state", emptyList(),
                    """{"capability":"SIM_SUBSCRIPTION","action":"slots"}""",
                    """{"count":2,"slots":[{"slotIndex":0,"carrierName":"EE","countryIso":"gb","dataRoaming":false}]}""",
                    handler = { p -> simSlots(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.STORAGE, "Read storage telemetry", listOf(
                ActionSpec("overview", "Space available and total, internal and external", emptyList(),
                    """{"capability":"STORAGE","action":"overview"}""",
                    """{"internalAvailableGb":41.2,"internalTotalGb":128.0,"externalAvailableGb":0.0,"externalTotalGb":0.0,"externalAvailable":false}""",
                    handler = { p -> storageOverview(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.USB, "Read connected USB devices", listOf(
                ActionSpec("list", "USB devices currently attached", emptyList(),
                    """{"capability":"USB","action":"list"}""",
                    """{"count":1,"devices":[{"name":"USB hub","vendorId":1118,"productId":42,"deviceClass":9}]}""",
                    handler = { p -> usbList(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.NFC, "Create NFC pairing records", listOf(
                ActionSpec("pairingRecord", "Build an NDEF record for pairing two devices", listOf(
                    ParamDoc("peerId", "string", true, "Identifier for the other device", "peer-1"),
                    ParamDoc("pin", "string", true, "Pairing PIN the peer must confirm", "123456")
                ), """{"capability":"NFC","action":"pairingRecord","peerId":"peer-1","pin":"123456"}""",
                    """{"records":[{"type":"android.com:pkg","data":"com.personal.base"}],"mime":"application/vnd.android.ndef"}""",
                    handler = { p -> nfcPairingRecord(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.SHARING, "Send things to other apps", listOf(
                ActionSpec("shareText", "Open the Android share sheet with some text", listOf(
                    ParamDoc("text", "string", true, "Text to share", "look at this"),
                    ParamDoc("title", "string", false, "Title for the chooser", "Share")
                ), """{"capability":"SHARING","action":"shareText","text":"look at this"}""",
                    """{"shared":true}""", handler = { p -> shareText(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.MEDIA_SESSION, "Control what the phone is playing", listOf(
                ActionSpec("playPause", "Toggle play and pause on the active media session", emptyList(),
                    """{"capability":"MEDIA_SESSION","action":"playPause"}""",
                    """{"sent":true}""", handler = { p -> mediaTransport(p) }),
                ActionSpec("next", "Skip to the next track", emptyList(),
                    """{"capability":"MEDIA_SESSION","action":"next"}""",
                    """{"sent":true}""", handler = { p -> mediaTransport(p) }),
                ActionSpec("previous", "Go back to the previous track", emptyList(),
                    """{"capability":"MEDIA_SESSION","action":"previous"}""",
                    """{"sent":true}""", handler = { p -> mediaTransport(p) }),
                ActionSpec("key", "Send a media key by name", listOf(
                    ParamDoc("key", "string", true, "One of play, pause, playPause, next, previous, stop", "playPause")
                ), """{"capability":"MEDIA_SESSION","action":"key","key":"next"}""",
                    """{"sent":true,"key":"next"}""", handler = { p -> mediaTransport(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.AUDIO_STREAM, "Stream microphone audio", listOf(
                ActionSpec("start", "Begin streaming raw microphone audio", listOf(
                    ParamDoc("sampleRate", "integer", false, "Samples per second", "16000")
                ), """{"capability":"AUDIO_STREAM","action":"start","sampleRate":16000}""",
                    """{"streaming":true,"sampleRate":16000}""", handler = { p -> audioStream(p) }),
                ActionSpec("stop", "Stop streaming", emptyList(),
                    """{"capability":"AUDIO_STREAM","action":"stop"}""",
                    """{"streaming":false}""", handler = { p -> audioStream(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.DOWNLOAD_MANAGER, "Download files over the network", listOf(
                ActionSpec(
                    "fetch",
                    "Download media to the Downloads folder, in the background",
                    listOf(
                        ParamDoc("url", "string", true, "The http(s) URL to fetch", "https://example.com/clip.mp4"),
                        ParamDoc("fileName", "string", false, "Name to save it under. Taken from the URL if omitted", "clip.mp4"),
                        ParamDoc("mediaType", "string", false, "MIME type, when the server does not say", "video/mp4")
                    ),
                    """{"capability":"DOWNLOAD_MANAGER","action":"fetch","url":"https://example.com/clip.mp4"}""",
                    """{"id":"dl_m0abc_0","status":"queued","percent":0,"fileName":"clip.mp4"}""",
                    handler = { p -> mediaFetch(p) }
                ),
                ActionSpec("status", "How far a download has got", listOf(
                    ParamDoc("id", "string", true, "The download id returned by fetch", "dl_m0abc_0")
                ), """{"capability":"DOWNLOAD_MANAGER","action":"status","id":"dl_m0abc_0"}""",
                    """{"id":"dl_m0abc_0","status":"downloading","percent":42,"bytesDownloaded":5242880,"totalBytes":12582912}""",
                    handler = { p -> mediaStatus(p) }),
                ActionSpec("list", "Downloads this platform has been asked to fetch, newest first", listOf(
                    ParamDoc("limit", "integer", false, "How many to return", "10")
                ), """{"capability":"DOWNLOAD_MANAGER","action":"list","limit":5}""",
                    """{"count":1,"downloads":[{"id":"dl_m0abc_0","status":"complete","percent":100,"fileName":"clip.mp4","savedPath":"Downloads/Android Star/clip.mp4"}]}""",
                    handler = { p -> mediaList(p) }),
                ActionSpec("cancel", "Stop a download", listOf(
                    ParamDoc("id", "string", true, "The download id to cancel", "dl_m0abc_0")
                ), """{"capability":"DOWNLOAD_MANAGER","action":"cancel","id":"dl_m0abc_0"}""",
                    """{"cancelled":true}""", handler = { p -> mediaCancel(p) }),
                ActionSpec(
                    "retry",
                    "Start a download again from the same URL",
                    listOf(
                        ParamDoc("id", "string", true, "The download id to retry", "dl_m0abc_0")
                    ),
                    """{"capability":"DOWNLOAD_MANAGER","action":"retry","id":"dl_m0abc_0"}""",
                    """{"id":"dl_n1b2c_3","status":"queued","percent":0,"retriedFrom":"dl_m0abc_0"}""",
                    handler = { p -> mediaRetry(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.PACKAGE_INSTALLER, "Install an APK from storage", listOf(
                ActionSpec("install", "Install an APK already on the device", listOf(
                    ParamDoc("path", "string", true, "Absolute path to the .apk file", "/data/local/tmp/app.apk")
                ), """{"capability":"PACKAGE_INSTALLER","action":"install","path":"/data/local/tmp/app.apk"}""",
                    """{"sessionId":7}""", handler = { p -> packageInstall(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.NEARBY_DEVICES, "Read the Bluetooth picture", listOf(
                ActionSpec("summary", "Paired and connected Bluetooth devices, with battery and health", emptyList(),
                    """{"capability":"NEARBY_DEVICES","action":"summary"}""",
                    """{"enabled":true,"paired":3,"connected":1,"devices":[{"name":"Headphones","address":"AA:BB:CC:DD:EE:FF","connected":true,"category":"audio","battery":72,"health":"ok","bondState":"bonded"}]}""",
                    handler = { p -> bluetoothSummary(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.LOCATION, "Read the last known location", listOf(
                ActionSpec("lastKnown", "The most recent fix the platform has seen", emptyList(),
                    """{"capability":"LOCATION","action":"lastKnown"}""",
                    """{"latitude":51.5074,"longitude":-0.1278,"accuracyMeters":12.0,"provider":"gps","timestamp":1752000000000}""",
                    handler = { p -> locationLastKnown(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.GEOFENCE_ACTIVITY, "Watch for the device entering and leaving places", listOf(
                ActionSpec("add", "Start watching a place", listOf(
                    ParamDoc("id", "string", true, "Name for this geofence", "home"),
                    ParamDoc("latitude", "number", true, "Latitude of the centre", "51.5074"),
                    ParamDoc("longitude", "number", true, "Longitude of the centre", "-0.1278"),
                    ParamDoc("radiusMeters", "number", false, "How far out counts as arriving", "150")
                ), """{"capability":"GEOFENCE_ACTIVITY","action":"add","id":"home","latitude":51.5074,"longitude":-0.1278}""",
                    """{"added":true,"id":"home","count":1}""", handler = { p -> geofenceAdd(p) }),
                ActionSpec("remove", "Stop watching a place", listOf(
                    ParamDoc("id", "string", true, "The geofence to remove", "home")
                ), """{"capability":"GEOFENCE_ACTIVITY","action":"remove","id":"home"}""",
                    """{"removed":true,"id":"home"}""", handler = { p -> geofenceRemove(p) }),
                ActionSpec("check", "Which watched places a location falls inside", listOf(
                    ParamDoc("latitude", "number", true, "Latitude to test", "51.5074"),
                    ParamDoc("longitude", "number", true, "Longitude to test", "-0.1278")
                ), """{"capability":"GEOFENCE_ACTIVITY","action":"check","latitude":51.5074,"longitude":-0.1278}""",
                    """{"inside":["home"]}""", handler = { p -> geofenceCheck(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.HEALTH_CONNECT, "Activity sensing", listOf(
                ActionSpec("startSteps", "Begin counting steps using the hardware step counter", emptyList(),
                    """{"capability":"HEALTH_CONNECT","action":"startSteps"}""",
                    """{"listening":true}""", handler = { p -> healthStartSteps(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.FILE_SERVER, "Serve files from the phone over the network", listOf(
                ActionSpec("start", "Start serving the shared folder", listOf(
                    ParamDoc("port", "integer", false, "Port to listen on", "8080")
                ), """{"capability":"FILE_SERVER","action":"start","port":8080}""",
                    """{"running":true,"port":8080,"files":0}""", handler = { p -> fileServer(p) }),
                ActionSpec("stop", "Stop serving", emptyList(),
                    """{"capability":"FILE_SERVER","action":"stop"}""",
                    """{"running":false}""", handler = { p -> fileServer(p) }),
                ActionSpec("status", "Whether it is serving, and on which port", emptyList(),
                    """{"capability":"FILE_SERVER","action":"status"}""",
                    """{"running":true,"port":8080,"files":2}""", handler = { p -> fileServer(p) }),
                ActionSpec("list", "The files currently shared", emptyList(),
                    """{"capability":"FILE_SERVER","action":"list"}""",
                    """{"count":1,"files":[{"name":"notes.txt","sizeBytes":2048,"formattedSize":"2.0 KB","modified":"2 hours ago","directory":false}]}""",
                    handler = { p -> fileServer(p) }),
                ActionSpec("put", "Add a text file to the shared folder", listOf(
                    ParamDoc("name", "string", true, "File name to write", "note.txt"),
                    ParamDoc("content", "string", true, "What to put in it", "hello")
                ), """{"capability":"FILE_SERVER","action":"put","name":"note.txt","content":"hello"}""",
                    """{"written":true,"name":"note.txt"}""", handler = { p -> fileServer(p) }),
                ActionSpec("delete", "Remove a file from the shared folder", listOf(
                    ParamDoc("name", "string", true, "File to delete", "note.txt")
                ), """{"capability":"FILE_SERVER","action":"delete","name":"note.txt"}""",
                    """{"deleted":true,"name":"note.txt"}""", handler = { p -> fileServer(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.NEARBY_CHAT, "Signed peer-to-peer messaging on the local network", listOf(
                ActionSpec("start", "Listen for nearby peers", listOf(
                    ParamDoc("port", "integer", false, "Port to listen on", "9090")
                ), """{"capability":"NEARBY_CHAT","action":"start","port":9090}""",
                    """{"listening":true,"port":9090}""", handler = { p -> nearbyChat(p) }),
                ActionSpec("stop", "Stop listening", emptyList(),
                    """{"capability":"NEARBY_CHAT","action":"stop"}""",
                    """{"listening":false}""", handler = { p -> nearbyChat(p) }),
                ActionSpec("peers", "Peers seen so far, and how much they are trusted", emptyList(),
                    """{"capability":"NEARBY_CHAT","action":"peers"}""",
                    """{"listening":true,"count":1,"peers":[{"id":"abc","name":"Pixel","address":"100.101.1.9:9090","connected":true,"trust":"ALWAYS"}]}""",
                    handler = { p -> nearbyChat(p) }),
                ActionSpec("connect", "Reach out to a peer by address", listOf(
                    ParamDoc("ip", "string", true, "The peer's IP address", "100.101.1.9"),
                    ParamDoc("port", "integer", false, "The peer's port", "9090"),
                    ParamDoc("name", "string", false, "Name to show for it", "Peer Device")
                ), """{"capability":"NEARBY_CHAT","action":"connect","ip":"100.101.1.9"}""",
                    """{"connecting":true}""", handler = { p -> nearbyChat(p) }),
                ActionSpec("send", "Send a signed message to connected peers", listOf(
                    ParamDoc("content", "string", true, "What to say", "on my way")
                ), """{"capability":"NEARBY_CHAT","action":"send","content":"on my way"}""",
                    """{"sent":true}""", handler = { p -> nearbyChat(p) }),
                ActionSpec("messages", "Messages received so far, newest first", listOf(
                    ParamDoc("limit", "integer", false, "How many to return", "20")
                ), """{"capability":"NEARBY_CHAT","action":"messages","limit":10}""",
                    """{"count":1,"messages":[{"from":"Pixel","content":"hi there","at":1752000000000}]}""",
                    handler = { p -> nearbyChat(p) }),
                ActionSpec("trust", "Decide whether to trust a peer that asked", listOf(
                    ParamDoc("decision", "string", true, "One of always, once, reject", "once")
                ), """{"capability":"NEARBY_CHAT","action":"trust","decision":"once"}""",
                    """{"resolved":true,"decision":"THIS_TIME_ONLY"}""", handler = { p -> nearbyChat(p) }),
                ActionSpec("forget", "Forget every trusted peer", emptyList(),
                    """{"capability":"NEARBY_CHAT","action":"forget"}""",
                    """{"cleared":true}""", handler = { p -> nearbyChat(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.ON_DEVICE_ML, "Recognise text in an image, on the device", listOf(
                ActionSpec(
                    "recognize",
                    "Read text out of an image, without anything leaving the phone",
                    listOf(
                        ParamDoc("path", "string", false, "Absolute path to an image file", "/sdcard/Pictures/shot.jpg"),
                        ParamDoc("base64", "string", false, "The image inline, instead of a path")
                    ),
                    """{"capability":"ON_DEVICE_ML","action":"recognize","path":"/sdcard/Pictures/shot.jpg"}""",
                    """{"text":"Invoice 4821","confidence":0.94,"labels":["invoice","receipt"]}""",
                    handler = { p -> mlRecognize(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.MEDIA_STREAM_ENCODER, "Hardware video encoders", listOf(
                ActionSpec("create", "Ask for a hardware video encoder and report what was negotiated", listOf(
                    ParamDoc("width", "integer", false, "Width in pixels", "1280"),
                    ParamDoc("height", "integer", false, "Height in pixels", "720"),
                    ParamDoc("bitRate", "integer", false, "Target bit rate in bits per second", "2000000")
                ), """{"capability":"MEDIA_STREAM_ENCODER","action":"create"}""",
                    """{"available":true,"width":1280,"height":720,"bitRate":2000000,"released":true}""",
                    handler = { p -> encoderCreate(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.OVERLAY_WINDOW, "Draw over other apps", listOf(
                ActionSpec(
                    "checkPermission",
                    "Whether this app may draw over other apps, and how to grant it",
                    emptyList(),
                    """{"capability":"OVERLAY_WINDOW","action":"checkPermission"}""",
                    """{"granted":false,"settingsAction":"android.settings.action.MANAGE_OVERLAY_PERMISSION"}""",
                    handler = { p -> overlayCheck(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.ALL_FILES_ACCESS, "Access every file on the device", listOf(
                ActionSpec(
                    "checkPermission",
                    "Whether this app may read and write all files, and how to grant it",
                    emptyList(),
                    """{"capability":"ALL_FILES_ACCESS","action":"checkPermission"}""",
                    """{"granted":false,"settingsAction":"android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"}""",
                    handler = { p -> allFilesCheck(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.VPN, "A device-wide VPN tunnel", listOf(
                ActionSpec("status", "Whether a tunnel is running, and whether one may be started", listOf(),
                    """{"capability":"VPN","action":"status"}""",
                    """{"active":false,"consentRequired":true,"settingsAction":"android.settings.VPN_SETTINGS"}""",
                    handler = { p -> vpnStatus(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.BIOMETRIC, "Fingerprint and device-credential authentication", listOf(
                ActionSpec(
                    "status",
                    "What this device can authenticate with, and whether a lock is set",
                    emptyList(),
                    """{"capability":"BIOMETRIC","action":"status"}""",
                    """{"hardware":true,"biometricAvailable":true,"deviceSecure":true,"lockEnabled":false,"appLocked":false}""",
                    handler = { p -> biometricStatus(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.INPUT_METHOD, "Typing into whatever is on screen", listOf(
                ActionSpec(
                    "status",
                    "Whether Android Star is the active keyboard, which is what typing needs",
                    emptyList(),
                    """{"capability":"INPUT_METHOD","action":"status"}""",
                    """{"isActiveKeyboard":false,"activeKeyboard":"com.google.android.inputmethod.latin/....LatinIME"}""",
                    handler = { p -> imeStatus(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.SPEECH_RECOGNITION, "Speech to text, on the device", listOf(
                ActionSpec("status", "Whether speech recognition is installed and usable here", listOf(),
                    """{"capability":"SPEECH_RECOGNITION","action":"status"}""",
                    """{"available":true,"recognizing":false,"lastTranscript":""}""",
                    handler = { p -> speechStatus(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.KEYGUARD, "The lock screen's own state", listOf(
                ActionSpec(
                    "status",
                    "Whether the screen is locked and whether the device has a secure lock set",
                    emptyList(),
                    """{"capability":"KEYGUARD","action":"status"}""",
                    """{"locked":false,"deviceSecure":true}""",
                    handler = { p -> keyguardStatus(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.RESTRICTED_SHELL, "Run a short allowlist of shell commands", listOf(
                ActionSpec(
                    "execute",
                    "Run one allowlisted command and return what it printed",
                    listOf(
                        ParamDoc(
                            "command", "string", true,
                            "The command and its arguments. Only ping, df, ip, getprop, uname, uptime, " +
                                "top and date are permitted; anything else is refused before it runs.",
                            "df -h"
                        )
                    ),
                    """{"capability":"RESTRICTED_SHELL","action":"execute","command":"df -h"}""",
                    """{"command":"df -h","exitCode":0,"allowed":true,"output":"Filesystem  Size  Used..."}""",
                    handler = { p -> runBlocking { shellExecute(p) } }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.PHONE, "The phone line, SIM and radio", listOf(
                ActionSpec(
                    "status",
                    "What the phone line looks like. Fields that need READ_PHONE_STATE are named " +
                        "in 'withheld' rather than returned blank, so a missing value is never " +
                        "mistaken for an empty one.",
                    emptyList(),
                    """{"capability":"PHONE","action":"status"}""",
                    """{"hasTelephony":true,"phoneCount":2,"permissionGranted":true,"networkOperator":"310260","simState":"READY"}""",
                    handler = { p -> phoneStatus(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.BACKGROUND_WORK, "Work that outlives the request that asked for it", listOf(
                ActionSpec(
                    "tasks",
                    "The work this platform will accept. Only registered tasks can be scheduled; " +
                        "nothing arbitrary is run.",
                    emptyList(),
                    """{"capability":"BACKGROUND_WORK","action":"tasks"}""",
                    """{"tasks":[{"name":"storage.scan","description":"Re-index downloaded files"}]}""",
                    handler = { p -> workTasks(p) }
                ),
                ActionSpec(
                    "enqueue",
                    "Hand a registered task to the phone and get an id back",
                    listOf(
                        ParamDoc("task", "string", true, "The name of a task, from 'tasks'", "storage.scan"),
                        ParamDoc("requireNetwork", "boolean", false, "Hold the work until the phone is online", "false"),
                        ParamDoc("requireCharging", "boolean", false, "Hold the work until the phone is charging", "false"),
                        ParamDoc("delaySeconds", "integer", false, "Hold the work at least this long", "0")
                    ),
                    """{"capability":"BACKGROUND_WORK","action":"enqueue","task":"storage.scan","requireCharging":true}""",
                    """{"enqueued":true,"task":"storage.scan","workId":"a1b2c3"}""",
                    handler = { p -> workEnqueue(p) }
                ),
                ActionSpec(
                    "list",
                    "Every unit of capability work, with the state it reached",
                    emptyList(),
                    """{"capability":"BACKGROUND_WORK","action":"list"}""",
                    """{"work":[{"task":"storage.scan","state":"SUCCEEDED","lastResult":"Indexed 12 files"}]}""",
                    handler = { p -> workList(p) }
                ),
                ActionSpec(
                    "cancel",
                    "Stop a unit of capability work",
                    listOf(ParamDoc("task", "string", true, "The name of a task", "storage.scan")),
                    """{"capability":"BACKGROUND_WORK","action":"cancel","task":"storage.scan"}""",
                    """{"cancelled":true}""",
                    handler = { p -> workCancel(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.ACCESSIBILITY, "Drive the screen, once the user has allowed it", listOf(
                ActionSpec(
                    "status",
                    "Whether the user has enabled this app in Settings. Nothing here works without it",
                    emptyList(),
                    """{"capability":"ACCESSIBILITY","action":"status"}""",
                    """{"enabled":true,"grantAction":"android.settings.ACCESSIBILITY_SETTINGS"}""",
                    handler = { p -> accessibilityStatus(p) }
                ),
                ActionSpec(
                    "tap",
                    "Tap at a point. Reports whether the tap was sent AND whether it actually ran",
                    listOf(
                        ParamDoc("x", "number", true, "Horizontal position in screen pixels", "540"),
                        ParamDoc("y", "number", true, "Vertical position in screen pixels", "1200"),
                        ParamDoc("durationMs", "integer", false, "How long to hold, in milliseconds", "50")
                    ),
                    """{"capability":"ACCESSIBILITY","action":"tap","x":540,"y":1200}""",
                    """{"submitted":true,"completed":true}""",
                    handler = { p -> accessibilityTap(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.REMOTE_INPUT, "Send taps and swipes to the screen", listOf(
                ActionSpec(
                    "status",
                    "Whether input can be sent at all right now",
                    emptyList(),
                    """{"capability":"REMOTE_INPUT","action":"status"}""",
                    """{"available":true,"grantAction":"android.settings.ACCESSIBILITY_SETTINGS"}""",
                    handler = { p -> remoteInputStatus(p) }
                ),
                ActionSpec(
                    "tap",
                    "Tap at a point",
                    listOf(
                        ParamDoc("x", "number", true, "Horizontal position in screen pixels", "540"),
                        ParamDoc("y", "number", true, "Vertical position in screen pixels", "1200")
                    ),
                    """{"capability":"REMOTE_INPUT","action":"tap","x":540,"y":1200}""",
                    """{"submitted":true,"completed":true}""",
                    handler = { p -> remoteInputTap(p) }
                ),
                ActionSpec(
                    "swipe",
                    "Drag from one point to another",
                    listOf(
                        ParamDoc("fromX", "number", true, "Start x", "540"),
                        ParamDoc("fromY", "number", true, "Start y", "1200"),
                        ParamDoc("toX", "number", true, "End x", "540"),
                        ParamDoc("toY", "number", true, "End y", "300"),
                        ParamDoc("durationMs", "integer", false, "How long the drag takes", "300")
                    ),
                    """{"capability":"REMOTE_INPUT","action":"swipe","fromX":540,"fromY":1200,"toX":540,"toY":300}""",
                    """{"submitted":true,"completed":true}""",
                    handler = { p -> remoteInputSwipe(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.NOTIFICATION_LISTENER, "Read notifications other apps post", listOf(
                ActionSpec(
                    "status",
                    "Whether the user has granted notification access. Nothing here works without it",
                    emptyList(),
                    """{"capability":"NOTIFICATION_LISTENER","action":"status"}""",
                    """{"granted":true,"connected":true}""",
                    handler = { p -> notificationStatus(p) }
                ),
                ActionSpec(
                    "list",
                    "The notifications on screen right now, read live from the system",
                    listOf(ParamDoc("limit", "integer", false, "How many to return, newest first", "50")),
                    """{"capability":"NOTIFICATION_LISTENER","action":"list","limit":20}""",
                    """{"count":2,"notifications":[{"package":"com.example","title":"Hi","text":"There"}]}""",
                    handler = { p -> notificationList(p) }
                ),
                ActionSpec(
                    "dismiss",
                    "Clear one notification from the shade",
                    listOf(ParamDoc("key", "string", true, "The key from 'list'", "0|com.example|1|null|42")),
                    """{"capability":"NOTIFICATION_LISTENER","action":"dismiss","key":"0|com.example|1|null|42"}""",
                    """{"dismissed":true}""",
                    handler = { p -> notificationDismiss(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.DOCUMENTS_PROVIDER, "The download folder, in any app's file picker", listOf(
                ActionSpec(
                    "status",
                    "Where this points and what it currently holds",
                    emptyList(),
                    """{"capability":"DOCUMENTS_PROVIDER","action":"status"}""",
                    """{"authority":"com.personal.base.documents","fileCount":3,"writable":false}""",
                    handler = { p -> documentsStatus(p) }
                ),
                ActionSpec(
                    "list",
                    "The same files the picker shows, with the same document ids",
                    listOf(ParamDoc("limit", "integer", false, "How many to return, newest first", "50")),
                    """{"capability":"DOCUMENTS_PROVIDER","action":"list"}""",
                    """{"count":1,"documents":[{"documentId":"a.jpg","name":"a.jpg","sizeBytes":2048}]}""",
                    handler = { p -> documentsList(p) }
                ),
                ActionSpec(
                    "delete",
                    "Delete one file by the id 'list' gave you",
                    listOf(ParamDoc("documentId", "string", true, "A document id, exactly as 'list' returned it", "a.jpg")),
                    """{"capability":"DOCUMENTS_PROVIDER","action":"delete","documentId":"a.jpg"}""",
                    """{"deleted":true,"documentId":"a.jpg"}""",
                    handler = { p -> documentsDelete(p) }
                )
            )
        ),
        CapabilitySpec(
            CapabilityType.CLIPBOARD, "Read and write the clipboard", listOf(
                ActionSpec("read", "Read clipboard text, if Android permits it", emptyList(),
                    """{"capability":"CLIPBOARD","action":"read"}""",
                    """{"text":"...","readable":true}""", handler = { p -> clipboardRead() }),
                ActionSpec("write", "Replace clipboard text", listOf(
                    ParamDoc("text", "string", true, "Text to place on the clipboard", "hello")
                ), """{"capability":"CLIPBOARD","action":"write","text":"hello"}""",
                    """{"written":true}""", handler = { p -> clipboardWrite(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CAMERA, "Take photos and record video", listOf(
                ActionSpec("cameras", "List the cameras this device has", emptyList(),
                    """{"capability":"CAMERA","action":"cameras"}""",
                    """{"cameras":2,"ids":["0","1"]}""", handler = { p -> cameraList(p) }),
                ActionSpec("capture", "Take a photo and save it as JPEG", listOf(
                    ParamDoc("cameraId", "string", false, "Which camera; defaults to the rear one", "0"),
                    ParamDoc("maxWidth", "integer", false, "Cap the output width in pixels", "1920")
                ), """{"capability":"CAMERA","action":"capture"}""",
                    """{"file":"/data/.../capture-1752.jpg","bytes":482913}""", handler = { p -> cameraCapture(p) }),
                ActionSpec("start", "Begin recording video", listOf(
                    ParamDoc("cameraId", "string", false, "Which camera; defaults to the rear one", "0")
                ), """{"capability":"CAMERA","action":"start"}""",
                    """{"recording":"/data/.../video-1752.mp4"}""", handler = { p -> cameraStart(p) }),
                ActionSpec("stop", "Finish recording and finalise the file", emptyList(),
                    """{"capability":"CAMERA","action":"stop"}""",
                    """{"file":"/data/.../video-1752.mp4","bytes":88213}""", handler = { p -> cameraStop(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.MICROPHONE, "Record audio", listOf(
                ActionSpec("record", "Record PCM audio to a file", listOf(
                    ParamDoc("durationMs", "integer", false, "How long to record", "2000")
                ), """{"capability":"MICROPHONE","action":"record","durationMs":3000}""",
                    """{"file":"...audio-1752.pcm","bytes":96000,"durationMs":3000,"format":"pcm_16bit_mono_16khz"}""",
                    requiresUserConsent = true, handler = { p -> micRecord(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.QR_BARCODE, "Generate and read QR codes and barcodes", listOf(
                ActionSpec("generate", "Encode text as a scannable QR and save it as PNG", listOf(
                    ParamDoc("content", "string", true, "Text to encode", "hello"),
                    ParamDoc("size", "integer", false, "Pixel size of the square image", "512")
                ), """{"capability":"QR_BARCODE","action":"generate","content":"hello"}""",
                    """{"file":"/data/.../exports/qr-1752.png"}""", handler = { p -> qrGenerate(p) }),
                ActionSpec("decode", "Read a barcode out of an image", listOf(
                    ParamDoc("file", "string", true, "Absolute path to an image, e.g. a camera capture", "/data/.../capture.jpg")
                ), """{"capability":"QR_BARCODE","action":"decode","file":"/data/.../capture.jpg"}""",
                    """{"content":"hello"}""", handler = { p -> qrDecode(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.SCREEN_CAPTURE, "Screen capture and casting", listOf(
                ActionSpec("state", "Report whether screen capture is running", emptyList(),
                    """{"capability":"SCREEN_CAPTURE","action":"state"}""",
                    """{"capturing":false,"note":"..."}""",
                    requiresUserConsent = true, handler = { p -> screenCaptureState() })
            )
        ),
        CapabilitySpec(
            CapabilityType.BATTERY_POWER, "Battery level, charging and health", listOf(
                ActionSpec("state", "Read the current battery state", emptyList(),
                    """{"capability":"BATTERY_POWER","action":"state"}""",
                    """{"levelPercentage":87,"isCharging":false,"batteryHealth":"Good"}""", handler = { p -> batteryState(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.DEVICE_INFO, "Model, OS, CPU and memory", listOf(
                ActionSpec("get", "Read device and hardware information", emptyList(),
                    """{"capability":"DEVICE_INFO","action":"get"}""",
                    """{"manufacturer":"Google","model":"Pixel 7","sdkInt":36}""", handler = { p -> deviceInfoGet() })
            )
        ),
        CapabilitySpec(
            CapabilityType.SENSORS, "Accelerometer, gyroscope, light and pressure", listOf(
                ActionSpec("get", "Read the latest sensor sample", emptyList(),
                    """{"capability":"SENSORS","action":"get"}""",
                    """{"accelX":0.12,"lightLux":142.0,"pressureHpa":1013.2}""", handler = { p -> sensorsGet() })
            )
        ),
        CapabilitySpec(
            CapabilityType.CONNECTIVITY, "Network state and Wi-Fi telemetry", listOf(
                ActionSpec("get", "Read the current network state", emptyList(),
                    """{"capability":"CONNECTIVITY","action":"get"}""",
                    """{"isConnected":true,"isWifi":true,"ssid":"Home","linkSpeedMbps":866}""", handler = { p -> connectivityGet() })
            )
        ),
        CapabilitySpec(
            CapabilityType.WIFI, "Wi-Fi state and Wi-Fi Direct peers", listOf(
                ActionSpec("state", "Whether Wi-Fi is on, and how many Direct peers are known", emptyList(),
                    """{"capability":"WIFI","action":"state"}""",
                    """{"enabled":true,"peers":0}""", handler = { p -> wifiState(p) }),
                ActionSpec("peers", "Discover Wi-Fi Direct peers", emptyList(),
                    """{"capability":"WIFI","action":"peers"}""",
                    """{"peers":2}""", requiresUserConsent = true, handler = { p -> wifiPeers(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.NETWORK_USAGE, "Data used over mobile and Wi-Fi", listOf(
                ActionSpec("get", "Read traffic counters", emptyList(),
                    """{"capability":"NETWORK_USAGE","action":"get"}""",
                    """{"mobileRxBytes":1024,"totalTxBytes":2048}""", handler = { p -> networkUsageGet() })
            )
        ),
        CapabilitySpec(
            CapabilityType.TEXT_TO_SPEECH, "Speak text aloud", listOf(
                ActionSpec("speak", "Speak the given text", listOf(
                    ParamDoc("text", "string", true, "What to say", "hello world")
                ), """{"capability":"TEXT_TO_SPEECH","action":"speak","text":"hello world"}""",
                    """{"speaking":true}""", handler = { p -> ttsSpeak(p) }),
                ActionSpec("stop", "Stop speaking", emptyList(),
                    """{"capability":"TEXT_TO_SPEECH","action":"stop"}""",
                    """{"stopped":true}""", handler = { p -> ttsStop(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.INSTALLED_APPS, "List and launch installed apps", listOf(
                ActionSpec("list", "List installed packages", listOf(
                    ParamDoc("includeSystem", "boolean", false, "Include preinstalled apps", "false")
                ), """{"capability":"INSTALLED_APPS","action":"list","includeSystem":false}""",
                    """{"count":142,"apps":[...]}""", handler = { p -> appsList(p) }),
                ActionSpec("launch", "Start an installed app", listOf(
                    ParamDoc("packageName", "string", true, "Package to launch", "com.android.settings")
                ), """{"capability":"INSTALLED_APPS","action":"launch","packageName":"com.android.settings"}""",
                    """{"launched":true}""", handler = { p -> appsLaunch(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.SYSTEM_SETTINGS, "Read system and secure settings", listOf(
                ActionSpec("get", "Read a system setting", listOf(
                    ParamDoc("key", "string", true, "Setting name, e.g. airplane_mode_on", "airplane_mode_on")
                ), """{"capability":"SYSTEM_SETTINGS","action":"get","key":"airplane_mode_on"}""",
                    """{"value":"0"}""", handler = { p -> settingsGet(p) }),
                ActionSpec("getsecure", "Read a secure setting", listOf(
                    ParamDoc("key", "string", true, "Setting name", "android_id")
                ), """{"capability":"SYSTEM_SETTINGS","action":"getsecure","key":"android_id"}""",
                    """{"value":"..."}""", handler = { p -> settingsGetSecure(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.FLASHLIGHT, "Torch control", listOf(
                ActionSpec("on", "Turn the torch on", emptyList(),
                    """{"capability":"FLASHLIGHT","action":"on"}""", """{"on":true}""") { p -> torch(p, true) },
                ActionSpec("off", "Turn the torch off", emptyList(),
                    """{"capability":"FLASHLIGHT","action":"off"}""", """{"on":false}""") { p -> torch(p, false) },
                ActionSpec("toggle", "Flip the torch", emptyList(),
                    """{"capability":"FLASHLIGHT","action":"toggle"}""", """{"on":true}""", handler = { p -> torchToggle(p) }),
                ActionSpec("state", "Whether the torch is on", emptyList(),
                    """{"capability":"FLASHLIGHT","action":"state"}""", """{"on":false}""", handler = { p -> torchState(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.DISPLAY, "Brightness, refresh rate and display state", listOf(
                ActionSpec("get", "Read display information", emptyList(),
                    """{"capability":"DISPLAY","action":"get"}""",
                    """{"brightnessPercent":64,"refreshRateHz":120.0}""", handler = { p -> displayGet() })
            )
        ),
        CapabilitySpec(
            CapabilityType.MEDIA_LIBRARY, "Recent images on the device", listOf(
                ActionSpec("recent", "List recently modified images", listOf(
                    ParamDoc("limit", "integer", false, "How many to return", "10")
                ), """{"capability":"MEDIA_LIBRARY","action":"recent","limit":5}""",
                    """{"count":5,"items":[...]}""", handler = { p -> mediaRecent(p) })
            )
        )
    )

    // =====================================================================
    // Handlers
    // =====================================================================

    private fun deviceHaptic(p: JsonObject): ServerResponse {
        val provider = provider<DeviceActionCapabilityProvider>(CapabilityType.DEVICE_ACTIONS)
            ?: return unsupported(CapabilityType.DEVICE_ACTIONS)
        val ms = p.long("durationMs") ?: 200L
        provider.vibrate(ms)
        return ServerResponse.json(buildJsonObject { put("vibrated", ms) }.toString())
    }

    private fun deviceTorch(p: JsonObject): ServerResponse {
        val provider = provider<DeviceActionCapabilityProvider>(CapabilityType.DEVICE_ACTIONS)
            ?: return unsupported(CapabilityType.DEVICE_ACTIONS)
        val on = p.boolean("enabled") ?: true
        return ServerResponse.json(buildJsonObject { put("torch", provider.setTorch(on)) }.toString())
    }

    private fun hapticPulse(p: JsonObject): ServerResponse {
        val provider = provider<DeviceActionCapabilityProvider>(CapabilityType.VIBRATION_HAPTICS)
            ?: return unsupported(CapabilityType.VIBRATION_HAPTICS)
        val ms = p.long("durationMs") ?: 200L
        provider.vibrate(ms)
        return ServerResponse.json(buildJsonObject { put("vibrated", ms) }.toString())
    }

    private fun notifyPost(p: JsonObject): ServerResponse {
        val provider = provider<NotificationCapabilityProvider>(CapabilityType.NOTIFICATIONS)
            ?: return unsupported(CapabilityType.NOTIFICATIONS)
        val title = p.string("title") ?: return badRequest("Missing 'title'")
        val link = p.string("link")

        val buttons = when (val raw = p["buttons"]) {
            null, is JsonNull -> emptyList()
            else -> runCatching { parseButtons(raw) }
                .getOrElse { return badRequest(it.message ?: "invalid 'buttons'") }
        }

        val posted = provider.postNotification(
            title = title,
            message = p.string("message") ?: "",
            priorityHigh = p.boolean("priorityHigh") ?: false,
            link = link,
            buttons = buttons
        ) ?: return ServerResponse(
            500, "application/json",
            """{"error":"Notification could not be posted","hint":"Check the POST_NOTIFICATIONS permission"}"""
        )

        return ServerResponse.json(
            buildJsonObject {
                put("posted", true)
                put("id", posted.id)
                put("opensLink", link != null)
                put("buttonsShown", posted.buttonsShown)
                put("buttonsDropped", buildJsonArray {
                    posted.buttonsDropped.forEach { add(JsonPrimitive(it)) }
                })
            }.toString()
        )
    }

    /**
     * Raised when the caller's button definitions are unusable.
     *
     * The message is what the caller gets back, so it names the offending button and says what was
     * wrong — a bare stack trace at someone using curl helps nobody.
     */
    private class BadButtonDefinition(message: String) : IllegalArgumentException(message)

    /**
     * Reads the caller's buttons.
     *
     * [NotificationButton] refuses a malformed button at construction, so building these is also how
     * an invalid definition is caught.
     */
    private fun parseButtons(raw: JsonElement): List<NotificationButton> {
        val array = raw as? JsonArray
            ?: throw BadButtonDefinition("'buttons' must be an array of button objects")

        return array.mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: throw BadButtonDefinition("buttons[$index] must be an object")

            val id = (obj["id"] as? JsonPrimitive)?.content
                ?: throw BadButtonDefinition("buttons[$index] needs a string 'id'")

            val input = (obj["input"] as? JsonObject)?.let { field ->
                val key = (field["key"] as? JsonPrimitive)?.content
                    ?: throw BadButtonDefinition("buttons[$index].input needs a string 'key'")
                NotificationInput(key = key, hint = (field["hint"] as? JsonPrimitive)?.content ?: "")
            }

            try {
                NotificationButton(
                    id = id,
                    label = (obj["label"] as? JsonPrimitive)?.content
                        ?: throw BadButtonDefinition("buttons[$index] ($id) needs a string 'label'"),
                    icon = (obj["icon"] as? JsonPrimitive)?.content,
                    input = input,
                    invoke = (obj["invoke"] as? JsonPrimitive)?.content,
                    dismissesNotification =
                        (obj["dismisses"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
                )
            } catch (e: BadButtonDefinition) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw BadButtonDefinition("buttons[$index] ($id): ${e.message}")
            }
        }
    }

    private fun notifyButtonLog(p: JsonObject): ServerResponse {
        val log = actionLog
            ?: return ServerResponse(
                501, "application/json",
                """{"error":"Button history is not being recorded on this build"}"""
            )
        val limit = p.int("limit") ?: 10
        val presses = log.recent(limit)

        return ServerResponse.json(
            buildJsonObject {
                put("count", presses.size)
                put("presses", buildJsonArray {
                    presses.forEach { record ->
                        add(
                            buildJsonObject {
                                put("buttonId", record.buttonId)
                                put("label", record.label)
                                put("notificationId", record.notificationId)
                                put("notificationTitle", record.notificationTitle)
                                record.typedText?.let { put("typedText", it) }
                                record.invokeTarget?.let { put("invokeTarget", it) }
                                record.invokeStatus?.let { put("invokeStatus", it) }
                                record.invokeBody?.let { put("invokeResult", it) }
                                put("at", record.atEpochMillis)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    /**
 * Reads the clipboard, saying plainly when Android will not allow it.
 *
 * Android 10 restricts clipboard reads to the focused app and the current keyboard. A background
 * host is neither, so the read comes back empty — and "empty" is a lie here, because it tells the
 * caller the clipboard is blank when in fact they were not allowed to look. A refusal answers 403
 * with what would lift it.
 */
    // =====================================================================
    // Wired capabilities
    // =====================================================================

    private fun alarmSet(p: JsonObject): ServerResponse {
        val provider = provider<AlarmTimerCapabilityProvider>(CapabilityType.ALARM_TIMER)
            ?: return unsupported(CapabilityType.ALARM_TIMER)
        val seconds = p.int("seconds") ?: return badRequest("Missing 'seconds'")
        if (seconds <= 0) return badRequest("'seconds' must be greater than zero")

        val ok = provider.setTimer(seconds, p.string("message") ?: "Timer")
        return if (ok) ServerResponse.json("""{"set":true,"seconds":$seconds}""")
        else ServerResponse(409, "application/json", """{"error":"Could not start the timer"}""")
    }

    private fun audioVolumeGet(p: JsonObject): ServerResponse {
        val provider = provider<AudioVolumeCapabilityProvider>(CapabilityType.AUDIO_VOLUME)
            ?: return unsupported(CapabilityType.AUDIO_VOLUME)
        val v = provider.getAudioVolumeStreams()
        return ServerResponse.json(
            buildJsonObject {
                put("media", v.mediaVolumePercent)
                put("ring", v.ringVolumePercent)
                put("voice", v.voiceVolumePercent)
                put("alarm", v.alarmVolumePercent)
                put("ringerMode", v.ringerMode)
            }.toString()
        )
    }

    private fun audioVolumeSet(p: JsonObject): ServerResponse {
        val provider = provider<AudioVolumeCapabilityProvider>(CapabilityType.AUDIO_VOLUME)
            ?: return unsupported(CapabilityType.AUDIO_VOLUME)
        val percent = p.int("percent") ?: return badRequest("Missing 'percent'")
        if (percent !in 0..100) return badRequest("'percent' must be between 0 and 100")

        provider.setMediaVolumePercent(percent)
        return ServerResponse.json("""{"set":true,"percent":$percent}""")
    }

    private fun calendarUpcoming(p: JsonObject): ServerResponse {
        val provider = provider<CalendarCapabilityProvider>(CapabilityType.CALENDAR)
            ?: return unsupported(CapabilityType.CALENDAR)
        val events = provider.getUpcomingEvents(p.int("limit") ?: 10)

        return ServerResponse.json(
            buildJsonObject {
                put("count", events.size)
                put("events", buildJsonArray {
                    events.forEach { e ->
                        add(
                            buildJsonObject {
                                put("id", e.id)
                                put("title", e.title)
                                put("start", e.startTime)
                                put("end", e.endTime)
                                e.description?.let { put("description", it) }
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    private fun callPlace(p: JsonObject): ServerResponse {
        val provider = provider<CallControlCapabilityProvider>(CapabilityType.CALL_CONTROL)
            ?: return unsupported(CapabilityType.CALL_CONTROL)
        val number = p.string("number") ?: return badRequest("Missing 'number'")

        return if (provider.placeCall(number)) ServerResponse.json("""{"placing":true}""")
        else ServerResponse(
            409, "application/json",
            """{"error":"Could not place the call","hint":"CALL_PHONE permission, or the dialler refused it"}"""
        )
    }

    private fun callLogCount(p: JsonObject): ServerResponse {
        val provider = provider<TelephonyCallLogCapabilityProvider>(CapabilityType.CALL_LOG)
            ?: return unsupported(CapabilityType.CALL_LOG)
        return ServerResponse.json("""{"count":${provider.getCallLogCount()}}""")
    }

    private fun clipboardBgWrite(p: JsonObject): ServerResponse {
        val provider = provider<ClipboardBackgroundCapabilityProvider>(CapabilityType.CLIPBOARD_BACKGROUND)
            ?: return unsupported(CapabilityType.CLIPBOARD_BACKGROUND)
        val text = p.string("text") ?: return badRequest("Missing 'text'")
        provider.setClipboard(text, p.string("label") ?: "Android Star")
        return ServerResponse.json("""{"written":true}""")
    }

    private fun contactsCount(p: JsonObject): ServerResponse {
        val provider = provider<ContactsAccountsCapabilityProvider>(CapabilityType.CONTACTS_ACCOUNTS)
            ?: return unsupported(CapabilityType.CONTACTS_ACCOUNTS)
        return ServerResponse.json("""{"contacts":${provider.getContactsCount()}}""")
    }

    private fun accountsCount(p: JsonObject): ServerResponse {
        val provider = provider<ContactsAccountsCapabilityProvider>(CapabilityType.CONTACTS_ACCOUNTS)
            ?: return unsupported(CapabilityType.CONTACTS_ACCOUNTS)
        return ServerResponse.json("""{"accounts":${provider.getAccountsCount()}}""")
    }

    private fun passkeySupport(p: JsonObject): ServerResponse {
        val provider = provider<CredentialManagerCapabilityProvider>(CapabilityType.CREDENTIAL_MANAGER)
            ?: return unsupported(CapabilityType.CREDENTIAL_MANAGER)
        return ServerResponse.json("""{"supported":${provider.getPasskeySupport()}}""")
    }

    private fun deviceAdminLock(p: JsonObject): ServerResponse {
        val provider = provider<DeviceAdminCapabilityProvider>(CapabilityType.DEVICE_ADMIN)
            ?: return unsupported(CapabilityType.DEVICE_ADMIN)
        return if (provider.lockScreenNow()) ServerResponse.json("""{"locked":true}""")
        else ServerResponse(
            409, "application/json",
            """{"error":"Could not lock the screen","hint":"Device administrator is not enabled on this device"}"""
        )
    }

    private fun displayGet(p: JsonObject): ServerResponse {
        val provider = provider<DisplayCapabilityProvider>(CapabilityType.DISPLAY)
            ?: return unsupported(CapabilityType.DISPLAY)
        val d = provider.getDisplayInfo()
        return ServerResponse.json(
            buildJsonObject {
                put("brightnessPercent", d.brightnessPercent)
                put("autoBrightness", d.isAutoBrightness)
                put("displayState", d.displayState)
                put("refreshRateHz", d.refreshRateHz)
            }.toString()
        )
    }

    private fun dndSet(p: JsonObject): ServerResponse {
        val provider = provider<DoNotDisturbCapabilityProvider>(CapabilityType.DO_NOT_DISTURB)
            ?: return unsupported(CapabilityType.DO_NOT_DISTURB)
        val name = p.string("filter")?.lowercase() ?: return badRequest("Missing 'filter'")

        // Named rather than numeric: an int straight into setInterruptionFilter is a trap for the
        // caller, and the constants have shifted across Android versions.
        val filter = when (name) {
            "off", "all" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "none" -> android.app.NotificationManager.INTERRUPTION_FILTER_NONE
            "alarms" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> return badRequest("'filter' must be one of off, priority, none, alarms")
        }

        return if (provider.setInterruptionFilter(filter)) {
            ServerResponse.json("""{"set":true,"filter":"$name"}""")
        } else {
            ServerResponse(
                403, "application/json",
                """{"error":"Do Not Disturb access is not granted","hint":"Settings → Notifications → Do Not Disturb access"}"""
            )
        }
    }

    private fun simSlots(p: JsonObject): ServerResponse {
        val provider = provider<SimSubscriptionCapabilityProvider>(CapabilityType.SIM_SUBSCRIPTION)
            ?: return unsupported(CapabilityType.SIM_SUBSCRIPTION)
        val slots = provider.getSimSlots()
        return ServerResponse.json(
            buildJsonObject {
                put("count", slots.size)
                put("slots", buildJsonArray {
                    slots.forEach { s ->
                        add(
                            buildJsonObject {
                                put("slotIndex", s.slotIndex)
                                put("carrierName", s.carrierName)
                                put("countryIso", s.countryIso)
                                put("dataRoaming", s.isDataRoaming)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    private fun storageOverview(p: JsonObject): ServerResponse {
        val provider = provider<StorageCapabilityProvider>(CapabilityType.STORAGE)
            ?: return unsupported(CapabilityType.STORAGE)
        val t = provider.getStorageTelemetry()
        return ServerResponse.json(
            buildJsonObject {
                put("internalAvailableGb", t.internalAvailableGb)
                put("internalTotalGb", t.internalTotalGb)
                put("externalAvailableGb", t.externalAvailableGb)
                put("externalTotalGb", t.externalTotalGb)
                put("externalAvailable", t.isExternalStorageAvailable)
            }.toString()
        )
    }

    private fun usbList(p: JsonObject): ServerResponse {
        val provider = provider<UsbCapabilityProvider>(CapabilityType.USB)
            ?: return unsupported(CapabilityType.USB)
        val devices = provider.getConnectedUsbDevices()
        return ServerResponse.json(
            buildJsonObject {
                put("count", devices.size)
                put("devices", buildJsonArray {
                    devices.forEach { d ->
                        add(
                            buildJsonObject {
                                put("name", d.deviceName)
                                put("vendorId", d.vendorId)
                                put("productId", d.productId)
                                put("deviceClass", d.deviceClass)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    private fun nfcPairingRecord(p: JsonObject): ServerResponse {
        val provider = provider<NfcCapabilityProvider>(CapabilityType.NFC)
            ?: return unsupported(CapabilityType.NFC)
        val peerId = p.string("peerId") ?: return badRequest("Missing 'peerId'")
        val pin = p.string("pin") ?: return badRequest("Missing 'pin'")

        val message = provider.createPairingRecord(peerId, pin)

        return ServerResponse.json(
            buildJsonObject {
                put("recordCount", message.records.size)
                put("bytes", message.toByteArray().size)
            }.toString()
        )
    }

    private fun fileServer(p: JsonObject): ServerResponse {
        val provider = provider<FileServerCapabilityProvider>(CapabilityType.FILE_SERVER)
            ?: return unsupported(CapabilityType.FILE_SERVER)

        return when (p.string("action")) {
            "start" -> {
                val port = p.int("port") ?: 8080
                if (port !in 1024..65535) return badRequest("'port' must be 1024-65535")
                if (!provider.startServer(port)) {
                    return ServerResponse(
                        409, "application/json",
                        """{"error":"Could not listen on port $port","hint":"Something else may already be using it"}"""
                    )
                }
                ServerResponse.json(
                    """{"running":true,"port":$port,"files":${provider.getFilesList().size}}"""
                )
            }
            "stop" -> {
                provider.stopServer()
                ServerResponse.json("""{"running":false}""")
            }
            "status" -> ServerResponse.json(
                """{"running":${provider.serverState.value.isRunning},"port":${provider.serverState.value.port},"files":${provider.getFilesList().size}}"""
            )
            "list" -> {
                val files = provider.getFilesList()
                ServerResponse.json(
                    buildJsonObject {
                        put("count", files.size)
                        put("files", buildJsonArray {
                            files.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("name", f.name)
                                        put("sizeBytes", f.sizeBytes)
                                        put("formattedSize", f.formattedSize)
                                        put("modified", f.lastModifiedFormatted)
                                        put("directory", f.isDirectory)
                                    }
                                )
                            }
                        })
                    }.toString()
                )
            }
            "put" -> {
                val name = p.string("name") ?: return badRequest("Missing 'name'")
                val content = p.string("content") ?: return badRequest("Missing 'content'")
                if (!provider.addCustomFile(name, content)) {
                    return ServerResponse(409, "application/json", """{"error":"Could not write $name"}""")
                }
                ServerResponse.json("""{"written":true,"name":"$name"}""")
            }
            "delete" -> {
                val name = p.string("name") ?: return badRequest("Missing 'name'")
                if (!provider.deleteFile(name)) {
                    return ServerResponse(404, "application/json", """{"error":"No such file: $name"}""")
                }
                ServerResponse.json("""{"deleted":true,"name":"$name"}""")
            }
            else -> badRequest("'action' must be start, stop, status, list, put or delete")
        }
    }

    private fun nearbyChat(p: JsonObject): ServerResponse {
        val provider = provider<NearbyChatCapabilityProvider>(CapabilityType.NEARBY_CHAT)
            ?: return unsupported(CapabilityType.NEARBY_CHAT)

        return when (p.string("action")) {
            "start" -> {
                val port = p.int("port") ?: 9090
                if (port !in 1024..65535) return badRequest("'port' must be 1024-65535")
                if (!provider.startServer(port)) {
                    return ServerResponse(
                        409, "application/json",
                        """{"error":"Could not listen on port $port","hint":"Something else may already be using it"}"""
                    )
                }
                ServerResponse.json("""{"listening":true,"port":$port}""")
            }
            "stop" -> {
                provider.stopServer()
                ServerResponse.json("""{"listening":false}""")
            }
            "peers" -> {
                val peers = provider.activePeers.value
                ServerResponse.json(
                    buildJsonObject {
                        put("listening", provider.isServerRunning.value)
                        put("count", peers.size)
                        put("peers", buildJsonArray {
                            peers.forEach { peer ->
                                add(
                                    buildJsonObject {
                                        put("id", peer.id)
                                        put("name", peer.name)
                                        put("address", peer.address)
                                        put("connected", peer.isConnected)
                                        put("trust", peer.trustLevel)
                                    }
                                )
                            }
                        })
                    }.toString()
                )
            }
            "connect" -> {
                val ip = p.string("ip") ?: return badRequest("Missing 'ip'")
                val port = p.int("port") ?: 9090
                if (port !in 1..65535) return badRequest("'port' must be 1-65535")
                // Deliberately fire-and-forget: the socket is opened on a background scope and the
                // handshake finishes on its own. Pretending it connected would be a lie.
                provider.connectToPeer(ip, port, p.string("name") ?: "Peer Device")
                ServerResponse.json("""{"connecting":true}""")
            }
            "send" -> {
                val content = p.string("content") ?: return badRequest("Missing 'content'")
                if (!provider.sendMessage(content)) {
                    return ServerResponse(
                        409, "application/json",
                        """{"error":"Nothing sent","hint":"No peers are connected yet"}"""
                    )
                }
                ServerResponse.json("""{"sent":true}""")
            }
            "messages" -> {
                val limit = p.int("limit") ?: 20
                val messages = provider.messages.value.takeLast(limit.coerceAtLeast(0))
                ServerResponse.json(
                    buildJsonObject {
                        put("count", messages.size)
                        put("messages", buildJsonArray {
                            messages.forEach { m ->
                                add(
                                    buildJsonObject {
                                        put("from", m.senderName)
                                        put("content", m.content)
                                        put("at", m.timestamp)
                                    }
                                )
                            }
                        })
                    }.toString()
                )
            }
            "trust" -> {
                val name = p.string("decision")?.lowercase() ?: return badRequest("Missing 'decision'")
                val decision = when (name) {
                    "always" -> PeerAuthDecision.ALWAYS
                    "once", "this-time-only" -> PeerAuthDecision.THIS_TIME_ONLY
                    "reject", "no" -> PeerAuthDecision.REJECT
                    else -> return badRequest("'decision' must be one of always, once, reject")
                }
                provider.resolveAuthRequest(decision)
                ServerResponse.json("""{"resolved":true,"decision":"$name"}""")
            }
            "forget" -> {
                provider.clearTrustedPeers()
                ServerResponse.json("""{"cleared":true}""")
            }
            else -> badRequest(
                "'action' must be start, stop, peers, connect, send, messages, trust or forget"
            )
        }
    }

    private fun mediaTransport(p: JsonObject): ServerResponse {
        val provider = provider<MediaSessionCapabilityProvider>(CapabilityType.MEDIA_SESSION)
            ?: return unsupported(CapabilityType.MEDIA_SESSION)

        val action = p.string("action") ?: return badRequest("Missing 'action'")
        val key = p.string("key")

        when (action) {
            "playPause" -> provider.playPause()
            "next" -> provider.nextTrack()
            "previous" -> provider.previousTrack()
            "key" -> {
                when (key?.lowercase()) {
                    "play", "playpause" -> provider.playPause()
                    "pause" -> provider.dispatchMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    "next" -> provider.nextTrack()
                    "previous", "prev" -> provider.previousTrack()
                    "stop" -> provider.dispatchMediaKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_STOP)
                    else -> return badRequest(
                        "'key' must be one of play, pause, playPause, next, previous, stop"
                    )
                }
            }
            else -> return badRequest("'action' must be playPause, next, previous or key")
        }
        return ServerResponse.json(
            """{"sent":true${key?.let { ",\"key\":\"$it\"" } ?: ""}}"""
        )
    }

    private fun audioStream(p: JsonObject): ServerResponse {
        val provider = provider<AudioStreamCapabilityProvider>(CapabilityType.AUDIO_STREAM)
            ?: return unsupported(CapabilityType.AUDIO_STREAM)

        return when (p.string("action")) {
            "start" -> {
                val rate = p.int("sampleRate") ?: 16_000
                if (rate !in 8_000..48_000) return badRequest("'sampleRate' must be 8000-48000")
                provider.startCapture(rate)
                ServerResponse.json("""{"streaming":true,"sampleRate":$rate}""")
            }
            "stop" -> {
                provider.stopCapture()
                ServerResponse.json("""{"streaming":false}""")
            }
            else -> badRequest("'action' must be start or stop")
        }
    }

    /**
     * Downloads media to the Downloads folder.
     *
     * Returns as soon as the job is queued. An HTTP request that blocked until a large file
     * finished would tie up the server and give the caller no way to stop it.
     */
    fun mediaFetch(p: JsonObject): ServerResponse {
        val provider = provider<MediaDownloadCapabilityProvider>(CapabilityType.DOWNLOAD_MANAGER)
            ?: return unsupported(CapabilityType.DOWNLOAD_MANAGER)
        val url = p.string("url") ?: return badRequest("Missing 'url'")

        val download = provider.start(url, p.string("fileName"), p.string("mediaType"))
            ?: return badRequest(
                "'url' must be http or https — file, content and intent schemes are refused"
            )

        return ServerResponse.json(
            buildJsonObject {
                put("id", download.id)
                put("status", download.status)
                put("percent", download.percent)
                put("fileName", download.fileName)
            }.toString()
        )
    }

    fun mediaStatus(p: JsonObject): ServerResponse {
        val provider = provider<MediaDownloadCapabilityProvider>(CapabilityType.DOWNLOAD_MANAGER)
            ?: return unsupported(CapabilityType.DOWNLOAD_MANAGER)
        val id = p.string("id") ?: return badRequest("Missing 'id'")

        val d = provider.get(id) ?: return ServerResponse(
            404, "application/json",
            """{"error":"No such download: $id","hint":"It may have been cleared; nothing is persisted across a restart"}"""
        )
        return ServerResponse.json(downloadJson(d).toString())
    }

    fun mediaList(p: JsonObject): ServerResponse {
        val provider = provider<MediaDownloadCapabilityProvider>(CapabilityType.DOWNLOAD_MANAGER)
            ?: return unsupported(CapabilityType.DOWNLOAD_MANAGER)
        val downloads = provider.recent(p.int("limit") ?: 10)

        return ServerResponse.json(
            buildJsonObject {
                put("count", downloads.size)
                put("downloads", buildJsonArray { downloads.forEach { add(downloadJson(it)) } })
            }.toString()
        )
    }

    fun mediaCancel(p: JsonObject): ServerResponse {
        val provider = provider<MediaDownloadCapabilityProvider>(CapabilityType.DOWNLOAD_MANAGER)
            ?: return unsupported(CapabilityType.DOWNLOAD_MANAGER)
        val id = p.string("id") ?: return badRequest("Missing 'id'")

        return if (provider.cancel(id)) ServerResponse.json("""{"cancelled":true}""")
        else ServerResponse(
            404, "application/json",
            """{"error":"Nothing to cancel for $id","hint":"It may have already finished"}"""
        )
    }

    /**
     * Starts a failed or finished download again.
     *
     * The URL is remembered on the record precisely so this is possible; without it a failure could
     * only be retried by the caller repeating the request they cannot now see.
     */
    fun mediaRetry(p: JsonObject): ServerResponse {
        val provider = provider<MediaDownloadCapabilityProvider>(CapabilityType.DOWNLOAD_MANAGER)
            ?: return unsupported(CapabilityType.DOWNLOAD_MANAGER)
        val id = p.string("id") ?: return badRequest("Missing 'id'")

        val previous = provider.get(id) ?: return ServerResponse(
            404, "application/json",
            """{"error":"No such download: $id"}"""
        )

        val next = provider.start(previous.url, previous.fileName, null) ?: return badRequest(
            "'${previous.url}' is no longer fetchable; only http and https are accepted"
        )

        return ServerResponse.json(
            buildJsonObject {
                put("id", next.id)
                put("status", next.status)
                put("percent", next.percent)
                put("retriedFrom", id)
            }.toString()
        )
    }

    private fun downloadJson(d: com.personal.base.platform.capabilities.MediaDownload) = buildJsonObject {
        put("id", d.id)
        put("url", d.url)
        put("status", d.status)
        put("percent", d.percent)
        put("bytesDownloaded", d.bytesDownloaded)
        put("totalBytes", d.totalBytes)
        put("fileName", d.fileName)
        d.savedPath?.let { put("savedPath", it) }
        d.error?.let { put("error", it) }
        put("startedAt", d.startedAt)
    }

    private fun packageInstall(p: JsonObject): ServerResponse {
        val provider = provider<PackageInstallerCapabilityProvider>(CapabilityType.PACKAGE_INSTALLER)
            ?: return unsupported(CapabilityType.PACKAGE_INSTALLER)
        val path = p.string("path") ?: return badRequest("Missing 'path'")

        val file = java.io.File(path)
        if (!file.exists()) {
            return badRequest("No such file: $path")
        }

        val sessionId = provider.installApk(file)
        return if (sessionId >= 0) ServerResponse.json("""{"sessionId":$sessionId}""")
        else ServerResponse(
            409, "application/json",
            """{"error":"Could not start the install session","hint":"Android requires user confirmation to install"}"""
        )
    }

    private fun bluetoothSummary(p: JsonObject): ServerResponse {
        val provider = provider<BluetoothCapabilityProvider>(CapabilityType.NEARBY_DEVICES)
            ?: return unsupported(CapabilityType.NEARBY_DEVICES)
        val s = provider.refreshDevices()

        return ServerResponse.json(
            buildJsonObject {
                put("enabled", s.isEnabled)
                put("paired", s.totalPairedDevices)
                put("connected", s.connectedDevicesCount)
                put("devices", buildJsonArray { s.devices.forEach { add(bluetoothJson(it)) } })
            }.toString()
        )
    }

    private fun bluetoothJson(d: BluetoothDeviceInfo) = buildJsonObject {
        put("name", d.name)
        put("address", d.address)
        put("connected", d.isConnected)
        put("category", d.deviceCategory)
        put("battery", d.batteryPercentage)
        put("health", d.healthStatus)
        put("bondState", d.bondState)
    }

    private fun locationLastKnown(p: JsonObject): ServerResponse {
        val provider = provider<LocationCapabilityProvider>(CapabilityType.LOCATION)
            ?: return unsupported(CapabilityType.LOCATION)

        // Nothing has arrived yet is a real and common answer; reporting it as a refusal would send
        // the caller looking for a permission that is not the problem.
        val loc: PlatformLocation? = provider.lastLocation.value
            ?: return ServerResponse(
                204, "application/json",
                """{"available":false,"hint":"No fix has arrived yet; ask again once location is warm"}"""
            )

        return ServerResponse.json(locationJson(loc!!).toString())
    }

    private fun locationJson(l: PlatformLocation) = buildJsonObject {
        put("available", true)
        put("latitude", l.latitude)
        put("longitude", l.longitude)
        put("accuracyMeters", l.accuracyMeters)
        put("provider", l.provider)
        put("timestamp", l.timestamp)
    }

    private fun geofenceAdd(p: JsonObject): ServerResponse {
        val provider = provider<GeofenceActivityCapabilityProvider>(CapabilityType.GEOFENCE_ACTIVITY)
            ?: return unsupported(CapabilityType.GEOFENCE_ACTIVITY)
        val id = p.string("id") ?: return badRequest("Missing 'id'")
        val lat = p.double("latitude") ?: return badRequest("Missing 'latitude'")
        val lon = p.double("longitude") ?: return badRequest("Missing 'longitude'")
        val radius = p.double("radiusMeters") ?: 150.0
        if (radius <= 0) return badRequest("'radiusMeters' must be greater than zero")

        provider.registerGeofence(PlatformGeofence(id, lat, lon, radius.toFloat()))
        return ServerResponse.json("""{"added":true,"id":"$id"}""")
    }

    private fun geofenceRemove(p: JsonObject): ServerResponse {
        val provider = provider<GeofenceActivityCapabilityProvider>(CapabilityType.GEOFENCE_ACTIVITY)
            ?: return unsupported(CapabilityType.GEOFENCE_ACTIVITY)
        val id = p.string("id") ?: return badRequest("Missing 'id'")
        provider.removeGeofence(id)
        return ServerResponse.json("""{"removed":true,"id":"$id"}""")
    }

    private fun geofenceCheck(p: JsonObject): ServerResponse {
        val provider = provider<GeofenceActivityCapabilityProvider>(CapabilityType.GEOFENCE_ACTIVITY)
            ?: return unsupported(CapabilityType.GEOFENCE_ACTIVITY)
        val lat = p.double("latitude") ?: return badRequest("Missing 'latitude'")
        val lon = p.double("longitude") ?: return badRequest("Missing 'longitude'")

        val results = provider.checkLocationAgainstGeofences(android.location.Location("api-check").apply {
            latitude = lat
            longitude = lon
        })

        return ServerResponse.json(
            buildJsonObject {
                put("inside", buildJsonArray { results.forEach { add(JsonPrimitive(it)) } })
            }.toString()
        )
    }

    private fun healthStartSteps(p: JsonObject): ServerResponse {
        val provider = provider<HealthConnectCapabilityProvider>(CapabilityType.HEALTH_CONNECT)
            ?: return unsupported(CapabilityType.HEALTH_CONNECT)
        provider.startStepTracking()
        return ServerResponse.json("""{"listening":true}""")
    }

    private fun shareText(p: JsonObject): ServerResponse {
        val provider = provider<SharingCapabilityProvider>(CapabilityType.SHARING)
            ?: return unsupported(CapabilityType.SHARING)
        val text = p.string("text") ?: return badRequest("Missing 'text'")
        provider.shareText(text, p.string("title") ?: "Share via Android Star")
        return ServerResponse.json("""{"shared":true}""")
    }

    // =====================================================================
    // Permission checks
    //
    // These cannot do the thing headlessly — a consent dialog needs an Activity, and an HTTP
    // request has none. What they can do is answer whether the grant exists and name the screen
    // that gives it. That is strictly better than the only alternative, which is trying the real
    // action and reading a refusal with no cause.
    // =====================================================================

    private fun overlayCheck(p: JsonObject): ServerResponse {
        val provider = provider<OverlayWindowCapabilityProvider>(CapabilityType.OVERLAY_WINDOW)
            ?: return unsupported(CapabilityType.OVERLAY_WINDOW)
        val canDraw = provider.canDrawOverlays
        return ServerResponse.json(
            buildJsonObject {
                put("granted", canDraw)
                put(
                    "settingsAction",
                    if (canDraw) "" else "android.settings.action.MANAGE_OVERLAY_PERMISSION"
                )
                put("showing", provider.isOverlayShowing.value)
            }.toString()
        )
    }

    private fun allFilesCheck(p: JsonObject): ServerResponse {
        val provider = provider<AllFilesAccessCapabilityProvider>(CapabilityType.ALL_FILES_ACCESS)
            ?: return unsupported(CapabilityType.ALL_FILES_ACCESS)
        val granted = provider.isExternalStorageManager
        return ServerResponse.json(
            buildJsonObject {
                put("granted", granted)
                put(
                    "settingsAction",
                    if (granted) "" else "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"
                )
            }.toString()
        )
    }

    private fun vpnStatus(p: JsonObject): ServerResponse {
        val provider = provider<VpnCapabilityProvider>(CapabilityType.VPN)
            ?: return unsupported(CapabilityType.VPN)
        // prepare() returning null means consent is already held, so a tunnel may be started.
        val consentHeld = provider.prepareVpnIntent() == null
        return ServerResponse.json(
            buildJsonObject {
                put("active", provider.isVpnActive.value)
                put("consentRequired", !consentHeld)
                put("settingsAction", "android.settings.VPN_SETTINGS")
            }.toString()
        )
    }

    private fun biometricStatus(p: JsonObject): ServerResponse {
        val provider = provider<BiometricAuthCapabilityProvider>(CapabilityType.BIOMETRIC)
            ?: return unsupported(CapabilityType.BIOMETRIC)
        return ServerResponse.json(
            buildJsonObject {
                put("hardware", provider.hasHardware)
                put("biometricAvailable", provider.isBiometricAvailable)
                put("deviceSecure", provider.isDeviceSecure)
                put("lockEnabled", provider.isLockEnabled.value)
                put("appLocked", provider.isAppLocked.value)
                put("description", provider.statusDescription)
            }.toString()
        )
    }

    private fun imeStatus(p: JsonObject): ServerResponse {
        val provider = provider<InputMethodCapabilityProvider>(CapabilityType.INPUT_METHOD)
            ?: return unsupported(CapabilityType.INPUT_METHOD)
        return ServerResponse.json(
            buildJsonObject {
                put("isActiveKeyboard", provider.isActiveKeyboard())
                put("activeKeyboard", provider.activeKeyboard() ?: "")
            }.toString()
        )
    }

    private fun speechStatus(p: JsonObject): ServerResponse {
        val provider = provider<SpeechRecognitionCapabilityProvider>(CapabilityType.SPEECH_RECOGNITION)
            ?: return unsupported(CapabilityType.SPEECH_RECOGNITION)
        return ServerResponse.json(
            buildJsonObject {
                put("available", provider.isRecognitionAvailable)
                put("recognizing", provider.isRecognizing.value)
                put("lastTranscript", provider.lastTranscript.value)
            }.toString()
        )
    }

    // =====================================================================
    // Real capabilities
    // =====================================================================

    private fun mlRecognize(p: JsonObject): ServerResponse {
        val provider = provider<OnDeviceMlCapabilityProvider>(CapabilityType.ON_DEVICE_ML)
            ?: return unsupported(CapabilityType.ON_DEVICE_ML)

        val bitmap = loadBitmap(p)
            ?: return badRequest("Supply either 'path' to an image file, or 'base64' image data")

        return try {
            val result = provider.recognizeTextFromImage(bitmap)
            ServerResponse.json(
                buildJsonObject {
                    put("text", result.detectedText)
                    put("confidence", result.confidence)
                    put("labels", buildJsonArray { result.labels.forEach { add(JsonPrimitive(it)) } })
                }.toString()
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Reads the image the caller named, refusing anything that is not a readable image file. */
    private fun loadBitmap(p: JsonObject): android.graphics.Bitmap? {
        val encoded = p.string("base64")
        if (!encoded.isNullOrBlank()) {
            val bytes = runCatching {
                android.util.Base64.decode(encoded.substringAfterLast(','), android.util.Base64.DEFAULT)
            }.getOrNull() ?: return null
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }

        val path = p.string("path") ?: return null
        val file = java.io.File(path)
        if (!file.exists() || !file.canRead()) return null
        return android.graphics.BitmapFactory.decodeFile(path)
    }

    private fun encoderCreate(p: JsonObject): ServerResponse {
        val provider = provider<MediaStreamEncoderCapabilityProvider>(CapabilityType.MEDIA_STREAM_ENCODER)
            ?: return unsupported(CapabilityType.MEDIA_STREAM_ENCODER)

        val width = p.int("width") ?: 1280
        val height = p.int("height") ?: 720
        val bitRate = p.int("bitRate") ?: 2_000_000
        if (width !in 16..4096 || height !in 16..4096) {
            return badRequest("'width' and 'height' must be 16-4096")
        }

        val codec = provider.createVideoEncoder(width, height, bitRate)
            // Released immediately: the caller asked what the device can do, not for a codec to
            // hold open. Handing one out and forgetting it would leak a hardware encoder.
            ?: return ServerResponse.json(
                """{"available":false,"hint":"No hardware video encoder could be created at ${width}x$height"}"""
            )

        return try {
            ServerResponse.json(
                buildJsonObject {
                    put("available", true)
                    put("width", width)
                    put("height", height)
                    put("bitRate", bitRate)
                    // Android's MediaCodec exposes no way to read back what was negotiated, so
                    // the answer is "an encoder was created for these parameters" and nothing more.
                    // Guessing the codec name would be worse than not claiming one.
                    put("released", true)
                }.toString()
            )
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun keyguardStatus(p: JsonObject): ServerResponse {
        val provider = provider<KeyguardCapabilityProvider>(CapabilityType.KEYGUARD)
            ?: return unsupported(CapabilityType.KEYGUARD)
        return ServerResponse.json(
            buildJsonObject {
                put("locked", provider.isKeyguardLocked)
                put("deviceSecure", provider.isDeviceSecure)
            }.toString()
        )
    }

    /**
     * Runs one allowlisted command.
     *
     * The allowlist lives in the provider and is checked before anything is executed, so a caller
     * cannot smuggle a binary past the name check by hiding it in an argument. A refused command
     * answers 403 with what was allowed — which is more useful than a bare refusal.
     */
    private suspend fun shellExecute(p: JsonObject): ServerResponse {
        val provider = provider<RestrictedShellCapabilityProvider>(CapabilityType.RESTRICTED_SHELL)
            ?: return unsupported(CapabilityType.RESTRICTED_SHELL)
        val command = p.string("command") ?: return badRequest("Missing 'command'")

        val result = provider.executeAllowlisted(command)

        if (!result.isAllowed) {
            return ServerResponse(
                403, "application/json",
                """{"error":"Not on the allowlist","command":"${result.command}","output":"${result.output}"}"""
            )
        }

        return ServerResponse.json(
            buildJsonObject {
                put("command", result.command)
                put("exitCode", result.exitCode)
                put("allowed", true)
                // Trimmed because a command like `top -n 1` can print thousands of lines, and a
                // caller who wanted all of it can ask for it in slices.
                put("output", result.output.trim().lines().take(MAX_OUTPUT_LINES).joinToString("\n"))
                put("truncated", result.output.trim().lines().size > MAX_OUTPUT_LINES)
            }.toString()
        )
    }

    private fun phoneStatus(p: JsonObject): ServerResponse {
        val provider = provider<PhoneCapabilityProvider>(CapabilityType.PHONE)
            ?: return unsupported(CapabilityType.PHONE)
        val status = provider.status()
        return ServerResponse.json(
            buildJsonObject {
                put("hasTelephony", status.hasTelephony)
                put("phoneCount", status.phoneCount)
                put("permissionGranted", status.permissionGranted)
                put("networkOperator", status.networkOperator)
                put("simOperator", status.simOperator)
                put("simCountryIso", status.simCountryIso)
                put("simState", status.simState)
                put("networkType", status.networkType)
                // Never a number: carriers return nothing for this even when permitted, and a
                // blank field here would read as "your number is blank" rather than "not knowable".
                put("phoneNumberAvailable", false)
                put("withheld", buildJsonArray { status.withheld.forEach { add(JsonPrimitive(it)) } })
                if (!status.permissionGranted) {
                    put("grantVia", "android.settings.APPLICATION_DETAILS_SETTINGS")
                }
            }.toString()
        )
    }

    private fun workTasks(p: JsonObject): ServerResponse {
        val provider = provider<BackgroundWorkCapabilityProvider>(CapabilityType.BACKGROUND_WORK)
            ?: return unsupported(CapabilityType.BACKGROUND_WORK)
        return ServerResponse.json(
            buildJsonObject {
                put("tasks", buildJsonArray {
                    provider.taskNames().forEach { name ->
                        add(
                            buildJsonObject {
                                put("name", name)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    /**
     * Schedules work.
     *
     * An unknown task name is refused rather than enqueued and failed later: naming a task that
     * does not exist is a caller mistake, and answering 200 with an id for work that will never
     * happen is precisely the fake success this platform does not ship.
     */
    private fun workEnqueue(p: JsonObject): ServerResponse {
        val provider = provider<BackgroundWorkCapabilityProvider>(CapabilityType.BACKGROUND_WORK)
            ?: return unsupported(CapabilityType.BACKGROUND_WORK)
        val name = p.string("task") ?: return badRequest("Missing 'task'")

        if (provider.taskNamed(name) == null) {
            return badRequest(
                "No task named '$name' is registered. Known tasks: " +
                    provider.taskNames().joinToString(", ").ifEmpty { "(none)" }
            )
        }

        val delay = p.long("delaySeconds") ?: 0L
        if (delay < 0 || delay > MAX_WORK_DELAY_SECONDS) {
            return badRequest("'delaySeconds' must be 0-${MAX_WORK_DELAY_SECONDS}")
        }

        val workId = provider.enqueue(
            name = name,
            payload = JsonObject(p.filterKeys { it != "capability" && it != "action" }),
            requireNetwork = p.boolean("requireNetwork") ?: false,
            requireCharging = p.boolean("requireCharging") ?: false,
            delaySeconds = delay
        ) ?: return ServerResponse(
            503, "application/json",
            """{"error":"WorkManager is not available, so nothing was scheduled"}"""
        )

        return ServerResponse.json(
            """{"enqueued":true,"task":"$name","workId":"$workId"}"""
        )
    }

    private fun workList(p: JsonObject): ServerResponse {
        val provider = provider<BackgroundWorkCapabilityProvider>(CapabilityType.BACKGROUND_WORK)
            ?: return unsupported(CapabilityType.BACKGROUND_WORK)
        return ServerResponse.json(
            buildJsonObject {
                put("work", buildJsonArray {
                    provider.list().forEach { work ->
                        add(
                            buildJsonObject {
                                put("task", work.name)
                                put("id", work.id)
                                put("state", work.state)
                                put("runAttemptCount", work.runAttemptCount)
                                put("lastResult", work.lastResult)
                                put("lastError", work.lastError)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    private fun workCancel(p: JsonObject): ServerResponse {
        val provider = provider<BackgroundWorkCapabilityProvider>(CapabilityType.BACKGROUND_WORK)
            ?: return unsupported(CapabilityType.BACKGROUND_WORK)
        val name = p.string("task") ?: return badRequest("Missing 'task'")
        val cancelled = provider.cancel(name)
        return if (cancelled) {
            ServerResponse.json("""{"cancelled":true,"task":"$name"}""")
        } else {
            // Nothing was running under that name. Saying so is better than reporting a
            // cancellation that did not cancel anything.
            ServerResponse(
                404, "application/json",
                """{"error":"Nothing is queued under '$name'","cancelled":false}"""
            )
        }
    }

    private fun accessibilityStatus(p: JsonObject): ServerResponse {
        val provider = provider<AccessibilityCapabilityProvider>(CapabilityType.ACCESSIBILITY)
            ?: return unsupported(CapabilityType.ACCESSIBILITY)
        return ServerResponse.json(
            buildJsonObject {
                put("enabled", provider.isEnabledInSettings)
                put("grantAction", provider.grantAction)
                // Stated plainly so nobody reads "enabled: false" as a platform fault: Android
                // requires a human to grant this one, and there is no API path that can do it.
                put("reason", if (provider.isEnabledInSettings) "" else "Not enabled by the user in Settings")
            }.toString()
        )
    }

    private fun accessibilityTap(p: JsonObject): ServerResponse {
        val provider = provider<AccessibilityCapabilityProvider>(CapabilityType.ACCESSIBILITY)
            ?: return unsupported(CapabilityType.ACCESSIBILITY)
        val x = p.double("x") ?: return badRequest("Missing 'x'")
        val y = p.double("y") ?: return badRequest("Missing 'y'")
        val duration = p.int("durationMs") ?: 50
        if (duration !in 1..5_000) return badRequest("'durationMs' must be 1-5000")

        return tapResponse(provider.injectTap(x.toFloat(), y.toFloat(), duration.toLong()))
    }

    private fun remoteInputStatus(p: JsonObject): ServerResponse {
        val provider = provider<RemoteInputCapabilityProvider>(CapabilityType.REMOTE_INPUT)
            ?: return unsupported(CapabilityType.REMOTE_INPUT)
        return ServerResponse.json(
            buildJsonObject {
                put("available", provider.isAvailable)
                put("grantAction", provider.grantAction)
                put(
                    "note",
                    "Typing is not here. It is INPUT_METHOD.typeText, which writes only to what " +
                        "the user is already typing into."
                )
            }.toString()
        )
    }

    private fun remoteInputTap(p: JsonObject): ServerResponse {
        val provider = provider<RemoteInputCapabilityProvider>(CapabilityType.REMOTE_INPUT)
            ?: return unsupported(CapabilityType.REMOTE_INPUT)
        val x = p.double("x") ?: return badRequest("Missing 'x'")
        val y = p.double("y") ?: return badRequest("Missing 'y'")
        val duration = p.int("durationMs") ?: 50
        if (duration !in 1..5_000) return badRequest("'durationMs' must be 1-5000")
        return tapResponse(provider.tap(x.toFloat(), y.toFloat(), duration.toLong()))
    }

    private fun remoteInputSwipe(p: JsonObject): ServerResponse {
        val provider = provider<RemoteInputCapabilityProvider>(CapabilityType.REMOTE_INPUT)
            ?: return unsupported(CapabilityType.REMOTE_INPUT)
        val fromX = p.double("fromX") ?: return badRequest("Missing 'fromX'")
        val fromY = p.double("fromY") ?: return badRequest("Missing 'fromY'")
        val toX = p.double("toX") ?: return badRequest("Missing 'toX'")
        val toY = p.double("toY") ?: return badRequest("Missing 'toY'")
        val duration = p.long("durationMs") ?: 300L
        if (duration !in 1..10_000) return badRequest("'durationMs' must be 1-10000")

        return tapResponse(
            provider.swipe(fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat(), duration)
        )
    }

    /**
     * Reports a gesture honestly.
     *
     * `submitted` is not `completed`. Android accepts a gesture and then cancels it because another
     * app was animating, and a caller told only "submitted" would believe a tap had landed where
     * the user saw nothing happen. So both are reported, and a refusal to even submit is 409.
     */
    private fun tapResponse(outcome: TapOutcome): ServerResponse {
        if (!outcome.submitted) {
            return ServerResponse(
                409, "application/json",
                buildJsonObject {
                    put("error", outcome.error ?: "The gesture was refused")
                    put("submitted", false)
                    put("grantAction", "android.settings.ACCESSIBILITY_SETTINGS")
                }.toString()
            )
        }
        return ServerResponse.json(
            buildJsonObject {
                put("submitted", true)
                put("completed", outcome.completed)
                if (outcome.error != null) put("note", outcome.error)
            }.toString()
        )
    }

    private fun notificationStatus(p: JsonObject): ServerResponse {
        val provider = provider<NotificationListenerCapabilityProvider>(CapabilityType.NOTIFICATION_LISTENER)
            ?: return unsupported(CapabilityType.NOTIFICATION_LISTENER)
        return ServerResponse.json(
            buildJsonObject {
                put("granted", provider.isGranted)
                put("connected", provider.isConnected)
                put("grantAction", provider.grantAction)
            }.toString()
        )
    }

    /**
     * The notifications on screen.
     *
     * "Not granted" is 403 and "granted but nothing is posted" is 200 with an empty list. Collapsing
     * those would make a missing permission look like a quiet phone, which is the kind of wrong
     * answer that gets trusted.
     */
    private fun notificationList(p: JsonObject): ServerResponse {
        val provider = provider<NotificationListenerCapabilityProvider>(CapabilityType.NOTIFICATION_LISTENER)
            ?: return unsupported(CapabilityType.NOTIFICATION_LISTENER)
        if (!provider.isGranted) {
            return ServerResponse(
                403, "application/json",
                """{"error":"Notification access has not been granted","grantAction":"${provider.grantAction}"}"""
            )
        }
        val limit = (p.int("limit") ?: 50).coerceIn(1, 200)
        val active = provider.activeNotifications()
            ?: return ServerResponse(
                503, "application/json",
                """{"error":"Granted, but the listener service has not connected yet"}"""
            )
        val shown = active.take(limit)
        return ServerResponse.json(
            buildJsonObject {
                put("count", shown.size)
                put("totalOnDevice", active.size)
                put("notifications", buildJsonArray {
                    shown.forEach { n ->
                        add(
                            buildJsonObject {
                                put("key", n.key)
                                put("package", n.packageName)
                                put("title", n.title)
                                put("text", n.text)
                                put("postTime", n.postTime)
                                put("isOngoing", n.isOngoing)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    private fun notificationDismiss(p: JsonObject): ServerResponse {
        val provider = provider<NotificationListenerCapabilityProvider>(CapabilityType.NOTIFICATION_LISTENER)
            ?: return unsupported(CapabilityType.NOTIFICATION_LISTENER)
        if (!provider.isGranted) {
            return ServerResponse(
                403, "application/json",
                """{"error":"Notification access has not been granted","grantAction":"${provider.grantAction}"}"""
            )
        }
        val key = p.string("key") ?: return badRequest("Missing 'key'")
        return if (provider.dismiss(key)) {
            ServerResponse.json("""{"dismissed":true,"key":"$key"}""")
        } else {
            // Not present, so nothing was dismissed. Saying so beats a success for nothing.
            ServerResponse(
                404, "application/json",
                """{"error":"No notification with that key is on screen","dismissed":false}"""
            )
        }
    }

    private fun documentsStatus(p: JsonObject): ServerResponse {
        val provider = provider<DocumentsProviderCapabilityProvider>(CapabilityType.DOCUMENTS_PROVIDER)
            ?: return unsupported(CapabilityType.DOCUMENTS_PROVIDER)
        val status = provider.status()
        return ServerResponse.json(
            buildJsonObject {
                put("authority", status.authority)
                put("folderPath", status.folderPath)
                put("folderExists", status.folderExists)
                put("fileCount", status.fileCount)
                put("totalBytes", status.totalBytes)
                put("rootId", status.androidStarRootId)
                // Read and delete only, stated so a caller does not plan around a picker that
                // cannot accept writes.
                put("writable", status.writable)
                put("pickerUri", "content://${status.authority}/root/${status.androidStarRootId}")
            }.toString()
        )
    }

    private fun documentsList(p: JsonObject): ServerResponse {
        val provider = provider<DocumentsProviderCapabilityProvider>(CapabilityType.DOCUMENTS_PROVIDER)
            ?: return unsupported(CapabilityType.DOCUMENTS_PROVIDER)
        val limit = (p.int("limit") ?: 50).coerceIn(1, 200)
        val docs = provider.list(limit)
        return ServerResponse.json(
            buildJsonObject {
                put("count", docs.size)
                put("documents", buildJsonArray {
                    docs.forEach { d ->
                        add(
                            buildJsonObject {
                                put("documentId", d.documentId)
                                put("name", d.name)
                                put("sizeBytes", d.sizeBytes)
                                put("lastModified", d.lastModified)
                            }
                        )
                    }
                })
            }.toString()
        )
    }

    private fun documentsDelete(p: JsonObject): ServerResponse {
        val provider = provider<DocumentsProviderCapabilityProvider>(CapabilityType.DOCUMENTS_PROVIDER)
            ?: return unsupported(CapabilityType.DOCUMENTS_PROVIDER)
        val id = p.string("documentId") ?: return badRequest("Missing 'documentId'")
        // Traversal is refused before anything is touched, and says so, rather than resolving to
        // some file that happens to exist.
        if (id.contains('/') || id.contains("..") || id.isBlank()) {
            return badRequest("'documentId' must be a plain file name, exactly as 'list' returned it")
        }
        return if (provider.delete(id)) {
            ServerResponse.json("""{"deleted":true,"documentId":"$id"}""")
        } else {
            ServerResponse(
                404, "application/json",
                """{"error":"No such document","deleted":false}"""
            )
        }
    }

    private fun clipboardRead(): ServerResponse {
        val provider = provider<ClipboardCapabilityProvider>(CapabilityType.CLIPBOARD)
            ?: return unsupported(CapabilityType.CLIPBOARD)

        return when (val read = provider.read()) {
            is ClipboardRead.Text -> ServerResponse.json(
                buildJsonObject {
                    put("text", read.value)
                    put("readable", true)
                }.toString()
            )

            ClipboardRead.Empty -> ServerResponse.json("""{"text":"","readable":true,"empty":true}""")

            ClipboardRead.Refused -> ServerResponse(
                403, "application/json",
                """{"error":"Android refused the clipboard read","readable":false,""" +
                    """"hint":"Android only lets the focused app or the current keyboard read the clipboard. """ +
                    """Set Android Star as your keyboard, or have an App read it from its own focused page.",""" +
                    """"writesStillWork":true}"""
            )
        }
    }

    private fun clipboardWrite(p: JsonObject): ServerResponse {
        val provider = provider<ClipboardCapabilityProvider>(CapabilityType.CLIPBOARD)
            ?: return unsupported(CapabilityType.CLIPBOARD)
        val text = p.string("text") ?: return badRequest("Missing 'text'")
        provider.setText(text)
        return ServerResponse.json("""{"written":true}""")
    }

    // ---- SMS ------------------------------------------------------------

    private fun smsCount(p: JsonObject): ServerResponse {
        val provider = provider<SmsCapabilityProvider>(CapabilityType.SMS)
            ?: return unsupported(CapabilityType.SMS)
        val count = provider.count() ?: return smsRefused()
        return ServerResponse.json("""{"count":$count}""")
    }

    private fun smsList(p: JsonObject): ServerResponse {
        val provider = provider<SmsCapabilityProvider>(CapabilityType.SMS)
            ?: return unsupported(CapabilityType.SMS)
        val messages = provider.list(
            limit = p.int("limit") ?: 20,
            search = p.string("search")
        ) ?: return smsRefused()

        return ServerResponse.json(
            buildJsonObject {
                put("count", messages.size)
                put("messages", buildJsonArray { messages.forEach { add(smsJson(it)) } })
            }.toString()
        )
    }

    private fun smsRead(p: JsonObject): ServerResponse {
        val provider = provider<SmsCapabilityProvider>(CapabilityType.SMS)
            ?: return unsupported(CapabilityType.SMS)
        val id = p.long("id") ?: return badRequest("Missing 'id'")
        val message = provider.read(id) ?: return smsRefused()
        return ServerResponse.json(smsJson(message).toString())
    }

    private fun smsJson(message: SmsMessage) = buildJsonObject {
        put("id", message.id)
        put("address", message.address)
        put("body", message.body)
        put("date", message.timestampMillis)
        put("type", message.type)
        put("read", message.read)
    }

    /**
     * A refused read.
     *
     * Deliberately not an empty result: an empty inbox and a blocked inbox look identical otherwise,
     * and acting on that difference is how a phone silently stops reporting its messages. The hint
     * names the screen where the grant actually lives, because the runtime dialog cannot grant it.
     */
    private fun smsRefused() = ServerResponse(
        403, "application/json",
        """{"error":"Reading SMS was refused","hint":"Grant it from ${RestrictedPermissions.settingsLabel()}; it cannot be granted from a runtime dialog"}"""
    )

    private fun cameraList(p: JsonObject): ServerResponse {
        val provider = provider<CameraMicCapabilityProvider>(CapabilityType.CAMERA)
            ?: return unsupported(CapabilityType.CAMERA)
        val cameras = provider.cameras()
        return ServerResponse.json(
            buildJsonObject {
                put("cameras", cameras.size)
                put("ids", cameras.joinToString(prefix = "[", postfix = "]") { "\"${it.id}\"" })
            }.toString()
        )
    }

    private fun cameraCapture(p: JsonObject): ServerResponse {
        val provider = provider<CameraMicCapabilityProvider>(CapabilityType.CAMERA)
            ?: return unsupported(CapabilityType.CAMERA)
        val file = provider.captureImage(p.string("cameraId"))
            ?: return ServerResponse(
                500, "application/json",
                """{"error":"Capture failed","hint":"Check CAMERA permission and that nothing else is using the camera"}"""
            )
        return ServerResponse.json(
            buildJsonObject {
                put("file", file.absolutePath)
                put("bytes", file.length())
            }.toString()
        )
    }

    private fun cameraStart(p: JsonObject): ServerResponse {
        val provider = provider<CameraMicCapabilityProvider>(CapabilityType.CAMERA)
            ?: return unsupported(CapabilityType.CAMERA)
        val file = provider.startRecording(p.string("cameraId"))
            ?: return ServerResponse(500, "application/json", """{"error":"Could not start recording"}""")
        return ServerResponse.json(buildJsonObject { put("recording", file.absolutePath) }.toString())
    }

    private fun cameraStop(p: JsonObject): ServerResponse {
        val provider = provider<CameraMicCapabilityProvider>(CapabilityType.CAMERA)
            ?: return unsupported(CapabilityType.CAMERA)
        val file = provider.stopRecording()
            ?: return ServerResponse(400, "application/json", """{"error":"Not recording"}""")
        return ServerResponse.json(
            buildJsonObject {
                put("file", file.absolutePath)
                put("bytes", file.length())
            }.toString()
        )
    }

    private fun micRecord(p: JsonObject): ServerResponse {
        val provider = provider<CameraMicCapabilityProvider>(CapabilityType.MICROPHONE)
            ?: return unsupported(CapabilityType.MICROPHONE)
        val ms = p.int("durationMs") ?: 2000
        val file = provider.recordAudioToFile(ms)
            ?: return ServerResponse(
                500, "application/json",
                """{"error":"Recording failed","hint":"Check RECORD_AUDIO permission"}"""
            )
        return ServerResponse.json(
            buildJsonObject {
                put("file", file.absolutePath)
                put("bytes", file.length())
                put("durationMs", ms)
                put("format", "pcm_16bit_mono_16khz")
            }.toString()
        )
    }

    private fun qrGenerate(p: JsonObject): ServerResponse {
        val provider = provider<QrBarcodeCapabilityProvider>(CapabilityType.QR_BARCODE)
            ?: return unsupported(CapabilityType.QR_BARCODE)
        val content = p.string("content") ?: return badRequest("Missing 'content'")
        val file = provider.generateToFile(content, size = p.int("size") ?: 512)
            ?: return ServerResponse(500, "application/json", """{"error":"Encode failed"}""")
        return ServerResponse.json(buildJsonObject { put("file", file.absolutePath) }.toString())
    }

    private fun qrDecode(p: JsonObject): ServerResponse {
        val provider = provider<QrBarcodeCapabilityProvider>(CapabilityType.QR_BARCODE)
            ?: return unsupported(CapabilityType.QR_BARCODE)
        val path = p.string("file") ?: return badRequest("Missing 'file'")
        val file = java.io.File(path)
        if (!file.exists()) return badRequest("No such file: $path")
        val text = provider.decodeFile(file)
            ?: return ServerResponse(422, "application/json", """{"error":"No barcode found in that image"}""")
        return ServerResponse.json(buildJsonObject { put("content", text) }.toString())
    }

    private fun screenCaptureState(): ServerResponse {
        val provider = provider<ScreenCaptureCapabilityProvider>(CapabilityType.SCREEN_CAPTURE)
            ?: return unsupported(CapabilityType.SCREEN_CAPTURE)
        return ServerResponse.json(
            buildJsonObject {
                put("capturing", provider.isCapturing.value)
                put(
                    "note",
                    "Screen capture needs one-time user consent, granted in the app UI. " +
                        "Once granted, capture runs from there."
                )
            }.toString()
        )
    }

    private fun batteryState(p: JsonObject): ServerResponse {
        val provider = provider<BatteryPowerCapabilityProvider>(CapabilityType.BATTERY_POWER)
            ?: return unsupported(CapabilityType.BATTERY_POWER)
        val s = provider.getBatteryPowerState()
        return ServerResponse.json(
            buildJsonObject {
                put("levelPercentage", s.levelPercentage)
                put("isCharging", s.isCharging)
                put("isPowerSaveMode", s.isPowerSaveMode)
                put("isDeviceIdleMode", s.isDeviceIdleMode)
                put("batteryHealth", s.batteryHealth)
            }.toString()
        )
    }

    private fun deviceInfoGet(): ServerResponse {
        val provider = provider<DeviceInfoCapabilityProvider>(CapabilityType.DEVICE_INFO)
            ?: return unsupported(CapabilityType.DEVICE_INFO)
        val i = provider.getDeviceInfo()
        return ServerResponse.json(
            buildJsonObject {
                put("manufacturer", i.manufacturer)
                put("model", i.model)
                put("androidVersion", i.androidVersion)
                put("sdkInt", i.sdkInt)
                put("cpuCores", i.cpuCores)
                put("totalRamMb", i.totalRamMb)
                put("availRamMb", i.availRamMb)
            }.toString()
        )
    }

    private fun sensorsGet(): ServerResponse {
        val provider = provider<SensorsCapabilityProvider>(CapabilityType.SENSORS)
            ?: return unsupported(CapabilityType.SENSORS)
        val s = provider.sensorData.value
        return ServerResponse.json(
            buildJsonObject {
                put("accelX", s.accelX); put("accelY", s.accelY); put("accelZ", s.accelZ)
                put("gyroX", s.gyroX); put("gyroY", s.gyroY); put("gyroZ", s.gyroZ)
                put("lightLux", s.lightLux); put("pressureHpa", s.pressureHpa)
            }.toString()
        )
    }

    private fun connectivityGet(): ServerResponse {
        val provider = provider<ConnectivityCapabilityProvider>(CapabilityType.CONNECTIVITY)
            ?: return unsupported(CapabilityType.CONNECTIVITY)
        val t = provider.networkState.value
        return ServerResponse.json(
            buildJsonObject {
                put("isConnected", t.isConnected); put("isWifi", t.isWifi)
                put("isCellular", t.isCellular); put("isMetered", t.isMetered)
                put("ssid", t.wifiSsid); put("ipAddress", t.wifiIpAddress)
                put("linkSpeedMbps", t.wifiLinkSpeedMbps)
                put("frequencyMHz", t.wifiFrequencyMhz)
                put("downstreamKbps", t.downstreamBandwidthKbps)
            }.toString()
        )
    }

    private fun wifiState(p: JsonObject): ServerResponse {
        val provider = provider<WifiCapabilityProvider>(CapabilityType.WIFI)
            ?: return unsupported(CapabilityType.WIFI)
        return ServerResponse.json(
            buildJsonObject {
                put("enabled", provider.isWifiEnabled)
                put("peers", provider.p2pPeers.value.size)
            }.toString()
        )
    }

    private fun wifiPeers(p: JsonObject): ServerResponse {
        val provider = provider<WifiCapabilityProvider>(CapabilityType.WIFI)
            ?: return unsupported(CapabilityType.WIFI)
        provider.discoverWifiDirectPeers()
        return ServerResponse.json(
            buildJsonObject { put("peers", provider.p2pPeers.value.size) }.toString()
        )
    }

    private fun networkUsageGet(): ServerResponse {
        val provider = provider<NetworkUsageCapabilityProvider>(CapabilityType.NETWORK_USAGE)
            ?: return unsupported(CapabilityType.NETWORK_USAGE)
        val m = provider.getTrafficMetrics()
        return ServerResponse.json(
            buildJsonObject {
                put("mobileRxBytes", m.mobileRxBytes); put("mobileTxBytes", m.mobileTxBytes)
                put("totalRxBytes", m.totalRxBytes); put("totalTxBytes", m.totalTxBytes)
            }.toString()
        )
    }

    private fun ttsSpeak(p: JsonObject): ServerResponse {
        val provider = provider<TextToSpeechCapabilityProvider>(CapabilityType.TEXT_TO_SPEECH)
            ?: return unsupported(CapabilityType.TEXT_TO_SPEECH)
        val text = p.string("text") ?: return badRequest("Missing 'text'")
        provider.speak(text)
        return ServerResponse.json("""{"speaking":true}""")
    }

    private fun ttsStop(p: JsonObject): ServerResponse {
        val provider = provider<TextToSpeechCapabilityProvider>(CapabilityType.TEXT_TO_SPEECH)
            ?: return unsupported(CapabilityType.TEXT_TO_SPEECH)
        provider.stop()
        return ServerResponse.json("""{"stopped":true}""")
    }

    private fun appsList(p: JsonObject): ServerResponse {
        val provider = provider<InstalledAppsCapabilityProvider>(CapabilityType.INSTALLED_APPS)
            ?: return unsupported(CapabilityType.INSTALLED_APPS)
        val apps = provider.getInstalledApps(p.boolean("includeSystem") ?: false)
        return ServerResponse.json(
            buildJsonObject {
                put("count", apps.size)
                put("apps", buildJsonArray {
                    apps.take(200).forEach { app ->
                        add(buildJsonObject {
                            put("packageName", app.packageName)
                            put("appName", app.appName)
                            put("isSystem", app.isSystemApp)
                            put("version", app.versionName)
                        })
                    }
                })
            }.toString()
        )
    }

    private fun appsLaunch(p: JsonObject): ServerResponse {
        val provider = provider<InstalledAppsCapabilityProvider>(CapabilityType.INSTALLED_APPS)
            ?: return unsupported(CapabilityType.INSTALLED_APPS)
        val pkg = p.string("packageName") ?: return badRequest("Missing 'packageName'")
        return ServerResponse.json(
            buildJsonObject { put("launched", provider.launchApp(pkg)) }.toString()
        )
    }

    private fun settingsGet(p: JsonObject): ServerResponse {
        val provider = provider<SystemSettingsCapabilityProvider>(CapabilityType.SYSTEM_SETTINGS)
            ?: return unsupported(CapabilityType.SYSTEM_SETTINGS)
        val key = p.string("key") ?: return badRequest("Missing 'key'")
        return ServerResponse.json(
            buildJsonObject { put("value", provider.getSystemSetting(key) ?: "") }.toString()
        )
    }

    private fun settingsGetSecure(p: JsonObject): ServerResponse {
        val provider = provider<SystemSettingsCapabilityProvider>(CapabilityType.SYSTEM_SETTINGS)
            ?: return unsupported(CapabilityType.SYSTEM_SETTINGS)
        val key = p.string("key") ?: return badRequest("Missing 'key'")
        return ServerResponse.json(
            buildJsonObject { put("value", provider.getSecureSetting(key) ?: "") }.toString()
        )
    }

    private fun torch(p: JsonObject, on: Boolean): ServerResponse {
        val provider = provider<FlashlightCapabilityProvider>(CapabilityType.FLASHLIGHT)
            ?: return unsupported(CapabilityType.FLASHLIGHT)
        return ServerResponse.json(
            buildJsonObject { put("on", provider.setTorch(on)) }.toString()
        )
    }

    private fun torchToggle(p: JsonObject): ServerResponse {
        val provider = provider<FlashlightCapabilityProvider>(CapabilityType.FLASHLIGHT)
            ?: return unsupported(CapabilityType.FLASHLIGHT)
        return ServerResponse.json(
            buildJsonObject { put("on", provider.toggleTorch()) }.toString()
        )
    }

    private fun torchState(p: JsonObject): ServerResponse {
        val provider = provider<FlashlightCapabilityProvider>(CapabilityType.FLASHLIGHT)
            ?: return unsupported(CapabilityType.FLASHLIGHT)
        return ServerResponse.json(buildJsonObject { put("on", provider.isTorchOn.value) }.toString())
    }

    private fun displayGet(): ServerResponse {
        val provider = provider<DisplayCapabilityProvider>(CapabilityType.DISPLAY)
            ?: return unsupported(CapabilityType.DISPLAY)
        val d = provider.getDisplayInfo()
        return ServerResponse.json(
            buildJsonObject {
                put("brightnessPercent", d.brightnessPercent)
                put("isAutoBrightness", d.isAutoBrightness)
                put("displayState", d.displayState)
                put("refreshRateHz", d.refreshRateHz)
            }.toString()
        )
    }

    private fun mediaRecent(p: JsonObject): ServerResponse {
        val provider = provider<MediaLibraryCapabilityProvider>(CapabilityType.MEDIA_LIBRARY)
            ?: return unsupported(CapabilityType.MEDIA_LIBRARY)
        val items = provider.getRecentImages(p.int("limit") ?: 10)
        return ServerResponse.json(
            buildJsonObject {
                put("count", items.size)
                put("items", buildJsonArray {
                    items.forEach { m ->
                        add(buildJsonObject {
                            put("id", m.id); put("title", m.title)
                            put("mimeType", m.mimeType); put("sizeBytes", m.sizeBytes)
                        })
                    }
                })
            }.toString()
        )
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : CapabilityProvider> provider(type: CapabilityType): T? =
        registry.getProvider(type) as? T

    private fun unsupported(type: CapabilityType) = ServerResponse(
        409, "application/json",
        """{"error":"Capability '${type.name}' has no provider on this device"}"""
    )

    private fun badRequest(reason: String) =
        ServerResponse(400, "application/json", """{"error":"$reason"}""")

    /** An unknown action returns what is actually possible, so a wrong guess is self-correcting. */
    private fun unknownAction(
        type: CapabilityType,
        spec: CapabilitySpec,
        attempted: String
    ): ServerResponse = ServerResponse(
        400, "application/json",
        """{"error":"Unknown action '$attempted' for ${type.name}",""" +
                """"available":${spec.names.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},""" +
                """"docs":"/api/capabilities/docs"}"""
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
