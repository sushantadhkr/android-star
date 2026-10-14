package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.NotificationActionContract
import com.personal.base.core.platform.NotificationButton
import com.personal.base.core.platform.NotificationInput
import com.personal.base.core.platform.ServerResponse
import com.personal.base.core.plugin.PlatformLocation
import com.personal.base.platform.capabilities.AudioStreamCapabilityProvider
import com.personal.base.platform.capabilities.BluetoothCapabilityProvider
import com.personal.base.platform.capabilities.BluetoothDeviceInfo
import com.personal.base.platform.capabilities.DownloadManagerCapabilityProvider
import com.personal.base.platform.capabilities.GeofenceActivityCapabilityProvider
import com.personal.base.platform.capabilities.HealthConnectCapabilityProvider
import com.personal.base.platform.capabilities.LocationCapabilityProvider
import com.personal.base.platform.capabilities.MediaSessionCapabilityProvider
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
import com.personal.base.platform.capabilities.DoNotDisturbCapabilityProvider
import com.personal.base.platform.capabilities.FlashlightCapabilityProvider
import com.personal.base.platform.capabilities.InstalledAppsCapabilityProvider
import com.personal.base.platform.capabilities.MediaLibraryCapabilityProvider
import com.personal.base.platform.capabilities.NetworkUsageCapabilityProvider
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.platform.capabilities.NfcCapabilityProvider
import com.personal.base.platform.capabilities.QrBarcodeCapabilityProvider
import com.personal.base.platform.capabilities.ScreenCaptureCapabilityProvider
import com.personal.base.platform.capabilities.SharingCapabilityProvider
import com.personal.base.platform.capabilities.SimSubscriptionCapabilityProvider
import com.personal.base.platform.capabilities.SmsCapabilityProvider
import com.personal.base.platform.capabilities.SmsMessage
import com.personal.base.platform.capabilities.SensorsCapabilityProvider
import com.personal.base.platform.capabilities.StorageCapabilityProvider
import com.personal.base.platform.capabilities.SystemSettingsCapabilityProvider
import com.personal.base.platform.capabilities.TelephonyCallLogCapabilityProvider
import com.personal.base.platform.capabilities.TextToSpeechCapabilityProvider
import com.personal.base.platform.capabilities.UsbCapabilityProvider
import com.personal.base.platform.capabilities.WifiCapabilityProvider
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
                ActionSpec("enqueue", "Queue a download and return its id", listOf(
                    ParamDoc("url", "string", true, "The http(s) URL to fetch", "https://example.com/file.zip"),
                    ParamDoc("fileName", "string", false, "Name to save it under", "file.zip"),
                    ParamDoc("description", "string", false, "Shown in the system download list", "Android Star")
                ), """{"capability":"DOWNLOAD_MANAGER","action":"enqueue","url":"https://example.com/file.zip"}""",
                    """{"id":4821}""", handler = { p -> downloadEnqueue(p) })
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

    private fun downloadEnqueue(p: JsonObject): ServerResponse {
        val provider = provider<DownloadManagerCapabilityProvider>(CapabilityType.DOWNLOAD_MANAGER)
            ?: return unsupported(CapabilityType.DOWNLOAD_MANAGER)
        val url = p.string("url") ?: return badRequest("Missing 'url'")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return badRequest("'url' must be http or https")
        }

        val name = p.string("fileName") ?: url.substringAfterLast('/').ifBlank { "download.bin" }
        val id = provider.enqueueDownload(url, name, p.string("description") ?: "Android Star")
        return ServerResponse.json("""{"id":$id}""")
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
