package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.ServerResponse
import com.personal.base.platform.capabilities.BatteryPowerCapabilityProvider
import com.personal.base.platform.capabilities.CameraMicCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardCapabilityProvider
import com.personal.base.platform.capabilities.ConnectivityCapabilityProvider
import com.personal.base.platform.capabilities.DeviceActionCapabilityProvider
import com.personal.base.platform.capabilities.DeviceInfoCapabilityProvider
import com.personal.base.platform.capabilities.DisplayCapabilityProvider
import com.personal.base.platform.capabilities.FlashlightCapabilityProvider
import com.personal.base.platform.capabilities.InstalledAppsCapabilityProvider
import com.personal.base.platform.capabilities.MediaLibraryCapabilityProvider
import com.personal.base.platform.capabilities.NetworkUsageCapabilityProvider
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.platform.capabilities.QrBarcodeCapabilityProvider
import com.personal.base.platform.capabilities.ScreenCaptureCapabilityProvider
import com.personal.base.platform.capabilities.SensorsCapabilityProvider
import com.personal.base.platform.capabilities.SystemSettingsCapabilityProvider
import com.personal.base.platform.capabilities.TextToSpeechCapabilityProvider
import com.personal.base.platform.capabilities.WifiCapabilityProvider
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
    private val logger: PlatformLogger
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
                ActionSpec("post", "Show a notification", listOf(
                    ParamDoc("title", "string", true, "Notification title", "Hello"),
                    ParamDoc("message", "string", false, "Body text", "from curl"),
                    ParamDoc("priorityHigh", "boolean", false, "Use the high-priority channel", "false")
                ), """{"capability":"NOTIFICATIONS","action":"post","title":"Hello","message":"from curl"}""",
                    """{"posted":true}""", handler = { p -> notifyPost(p) })
            )
        ),
        CapabilitySpec(
            CapabilityType.CLIPBOARD, "Read and write the clipboard", listOf(
                ActionSpec("read", "Read clipboard text", emptyList(),
                    """{"capability":"CLIPBOARD","action":"read"}""", """{"text":"..."}""") { notifyReadClipboard() },
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
        provider.postNotification(title, p.string("message") ?: "", p.boolean("priorityHigh") ?: false)
        return ServerResponse.json("""{"posted":true}""")
    }

    private fun notifyReadClipboard(): ServerResponse {
        val provider = provider<ClipboardCapabilityProvider>(CapabilityType.CLIPBOARD)
            ?: return unsupported(CapabilityType.CLIPBOARD)
        return ServerResponse.json(buildJsonObject { put("text", provider.getText() ?: "") }.toString())
    }

    private fun clipboardWrite(p: JsonObject): ServerResponse {
        val provider = provider<ClipboardCapabilityProvider>(CapabilityType.CLIPBOARD)
            ?: return unsupported(CapabilityType.CLIPBOARD)
        val text = p.string("text") ?: return badRequest("Missing 'text'")
        provider.setText(text)
        return ServerResponse.json("""{"written":true}""")
    }

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
    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
}
