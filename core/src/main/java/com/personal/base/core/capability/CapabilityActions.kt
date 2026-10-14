package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.ServerResponse
import com.personal.base.platform.capabilities.BatteryPowerCapabilityProvider
import com.personal.base.platform.capabilities.CameraMicCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardCapabilityProvider
import com.personal.base.platform.capabilities.ConnectivityCapabilityProvider
import com.personal.base.platform.capabilities.DisplayCapabilityProvider
import com.personal.base.platform.capabilities.FlashlightCapabilityProvider
import com.personal.base.platform.capabilities.InstalledAppsCapabilityProvider
import com.personal.base.platform.capabilities.MediaLibraryCapabilityProvider
import com.personal.base.platform.capabilities.QrBarcodeCapabilityProvider
import com.personal.base.platform.capabilities.ScreenCaptureCapabilityProvider
import com.personal.base.platform.capabilities.SystemSettingsCapabilityProvider
import com.personal.base.platform.capabilities.TextToSpeechCapabilityProvider
import com.personal.base.platform.capabilities.DeviceActionCapabilityProvider
import com.personal.base.platform.capabilities.DeviceInfoCapabilityProvider
import com.personal.base.platform.capabilities.NetworkUsageCapabilityProvider
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.platform.capabilities.SensorsCapabilityProvider
import com.personal.base.platform.capabilities.WifiCapabilityProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Invokes a capability on request.
 *
 * Only capabilities that genuinely do something are listed here. A capability with no implementation
 * behind it is reported as unsupported rather than pretending to succeed, so the API never lies
 * about what the platform can actually do.
 */
class CapabilityActions(
    private val registry: PlatformCapabilityRegistry,
    private val logger: PlatformLogger
) {

    /** The actions a capability really supports. */
    fun invoke(
        type: CapabilityType,
        action: String,
        payload: JsonObject
    ): ServerResponse = runCatching {
        when (type) {
            CapabilityType.DEVICE_ACTIONS -> deviceActions(action, payload)
            CapabilityType.VIBRATION_HAPTICS -> haptics(action, payload)
            CapabilityType.NOTIFICATIONS -> notifications(action, payload)
            CapabilityType.CLIPBOARD -> clipboard(action, payload)
            CapabilityType.BATTERY_POWER -> battery(action)
            CapabilityType.CAMERA -> camera(action, payload)
            CapabilityType.MICROPHONE -> microphone(action, payload)
            CapabilityType.QR_BARCODE -> qrBarcode(action, payload)
            CapabilityType.SCREEN_CAPTURE -> screenCapture(action)
            CapabilityType.TEXT_TO_SPEECH -> textToSpeech(action, payload)
            CapabilityType.INSTALLED_APPS -> installedApps(action, payload)
            CapabilityType.SYSTEM_SETTINGS -> systemSettings(action, payload)
            CapabilityType.FLASHLIGHT -> flashlight(action, payload)
            CapabilityType.DISPLAY -> display()
            CapabilityType.MEDIA_LIBRARY -> mediaLibrary(action, payload)
            CapabilityType.DEVICE_INFO -> deviceInfo()
            CapabilityType.SENSORS -> sensors()
            CapabilityType.CONNECTIVITY -> connectivity()
            CapabilityType.WIFI -> wifi(action)
            CapabilityType.NETWORK_USAGE -> networkUsage()
            else -> ServerResponse(
                501, "application/json",
                """{"error":"Capability '${type.name}' has no callable action","supported":[]}"""
            )
        }
    }.getOrElse {
        logger.e("CapabilityActions", "Action '$action' on ${type.name} failed: ${it.message}")
        ServerResponse(500, "application/json", """{"error":"${it.message}"}""")
    }

    // ---- actions -------------------------------------------------------

    private fun deviceActions(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<DeviceActionCapabilityProvider>(CapabilityType.DEVICE_ACTIONS)
            ?: return unsupported(CapabilityType.DEVICE_ACTIONS)
        return when (action.lowercase()) {
            "haptic", "vibrate" -> {
                val ms = payload.long("durationMs") ?: 200L
                provider.vibrate(ms)
                ServerResponse.json(buildJsonObject { put("vibrated", ms) }.toString())
            }
            "torch" -> {
                val on = payload.boolean("enabled") ?: true
                val ok = provider.setTorch(on)
                ServerResponse.json(buildJsonObject { put("torch", ok) }.toString())
            }
            else -> unknownAction(action, listOf("haptic", "torch"))
        }
    }

    private fun haptics(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<DeviceActionCapabilityProvider>(CapabilityType.VIBRATION_HAPTICS)
            ?: return unsupported(CapabilityType.VIBRATION_HAPTICS)
        return when (action.lowercase()) {
            "pulse", "vibrate", "haptic" -> {
                val ms = payload.long("durationMs") ?: 200L
                provider.vibrate(ms)
                ServerResponse.json(buildJsonObject { put("vibrated", ms) }.toString())
            }
            else -> unknownAction(action, listOf("pulse"))
        }
    }

    private fun notifications(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<NotificationCapabilityProvider>(CapabilityType.NOTIFICATIONS)
            ?: return unsupported(CapabilityType.NOTIFICATIONS)
        return when (action.lowercase()) {
            "post", "send" -> {
                val title = payload.string("title") ?: return badRequest("Missing 'title'")
                val message = payload.string("message") ?: ""
                val high = payload.boolean("priorityHigh") ?: false
                provider.postNotification(title, message, high)
                ServerResponse.json("""{"posted":true}""")
            }
            else -> unknownAction(action, listOf("post"))
        }
    }

    private fun clipboard(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<ClipboardCapabilityProvider>(CapabilityType.CLIPBOARD)
            ?: return unsupported(CapabilityType.CLIPBOARD)
        return when (action.lowercase()) {
            "read" -> ServerResponse.json(
                buildJsonObject { put("text", provider.getText() ?: "") }.toString()
            )
            "write", "set" -> {
                val text = payload.string("text") ?: return badRequest("Missing 'text'")
                provider.setText(text)
                ServerResponse.json("""{"written":true}""")
            }
            else -> unknownAction(action, listOf("read", "write"))
        }
    }

    private fun battery(action: String): ServerResponse {
        val provider = provider<BatteryPowerCapabilityProvider>(CapabilityType.BATTERY_POWER)
            ?: return unsupported(CapabilityType.BATTERY_POWER)
        if (!action.equals("state", ignoreCase = true) && !action.equals("get", ignoreCase = true)) {
            return unknownAction(action, listOf("state"))
        }
        val state = provider.getBatteryPowerState()
        return ServerResponse.json(
            buildJsonObject {
                put("levelPercentage", state.levelPercentage)
                put("isCharging", state.isCharging)
                put("isPowerSaveMode", state.isPowerSaveMode)
                put("isDeviceIdleMode", state.isDeviceIdleMode)
                put("batteryHealth", state.batteryHealth)
            }.toString()
        )
    }

    private fun camera(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<CameraMicCapabilityProvider>(CapabilityType.CAMERA)
            ?: return unsupported(CapabilityType.CAMERA)
        val cameraId = payload.string("cameraId")
        return when (action.lowercase()) {
            "cameras", "list" -> ServerResponse.json(
                buildJsonObject {
                    put("cameras", provider.cameras().size)
                    put(
                        "ids",
                        provider.cameras().joinToString(prefix = "[", postfix = "]") { "\"${it.id}\"" }
                    )
                }.toString()
            )
            "capture", "photo", "still" -> {
                val file = provider.captureImage(cameraId)
                    ?: return ServerResponse(
                        500, "application/json",
                        """{"error":"Capture failed","hint":"Check CAMERA permission and that no other app is using the camera"}"""
                    )
                ServerResponse.json(
                    buildJsonObject {
                        put("file", file.absolutePath)
                        put("bytes", file.length())
                    }.toString()
                )
            }
            "start" -> {
                val file = provider.startRecording(cameraId)
                    ?: return ServerResponse(
                        500, "application/json",
                        """{"error":"Could not start recording"}"""
                    )
                ServerResponse.json(buildJsonObject { put("recording", file.absolutePath) }.toString())
            }
            "stop" -> {
                val file = provider.stopRecording()
                    ?: return ServerResponse(
                        400, "application/json",
                        """{"error":"Not recording"}"""
                    )
                ServerResponse.json(
                    buildJsonObject {
                        put("file", file.absolutePath)
                        put("bytes", file.length())
                    }.toString()
                )
            }
            else -> unknownAction(action, listOf("cameras", "capture", "start", "stop"))
        }
    }

    private fun microphone(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<CameraMicCapabilityProvider>(CapabilityType.MICROPHONE)
            ?: return unsupported(CapabilityType.MICROPHONE)
        return when (action.lowercase()) {
            "record", "capture" -> {
                val ms = payload.int("durationMs") ?: 2000
                val file = provider.recordAudioToFile(ms)
                    ?: return ServerResponse(
                        500, "application/json",
                        """{"error":"Recording failed","hint":"Check RECORD_AUDIO permission"}"""
                    )
                ServerResponse.json(
                    buildJsonObject {
                        put("file", file.absolutePath)
                        put("bytes", file.length())
                        put("durationMs", ms)
                        put("format", "pcm_16bit_mono_16khz")
                    }.toString()
                )
            }
            else -> unknownAction(action, listOf("record"))
        }
    }

    private fun qrBarcode(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<QrBarcodeCapabilityProvider>(CapabilityType.QR_BARCODE)
            ?: return unsupported(CapabilityType.QR_BARCODE)
        return when (action.lowercase()) {
            "generate", "encode" -> {
                val content = payload.string("content")
                    ?: return badRequest("Missing 'content'")
                val file = provider.generateToFile(content)
                    ?: return ServerResponse(500, "application/json", """{"error":"Encode failed"}""")
                ServerResponse.json(buildJsonObject { put("file", file.absolutePath) }.toString())
            }
            "decode", "read" -> {
                val path = payload.string("file") ?: return badRequest("Missing 'file'")
                val file = java.io.File(path)
                if (!file.exists()) return badRequest("No such file: $path")
                val text = provider.decodeFile(file)
                    ?: return ServerResponse(
                        422, "application/json",
                        """{"error":"No barcode found in that image"}"""
                    )
                ServerResponse.json(buildJsonObject { put("content", text) }.toString())
            }
            else -> unknownAction(action, listOf("generate", "decode"))
        }
    }

    private fun screenCapture(action: String): ServerResponse {
        val provider = provider<ScreenCaptureCapabilityProvider>(CapabilityType.SCREEN_CAPTURE)
            ?: return unsupported(CapabilityType.SCREEN_CAPTURE)
        if (!action.equals("state", ignoreCase = true)) {
            return unknownAction(action, listOf("state"))
        }
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

    private fun textToSpeech(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<TextToSpeechCapabilityProvider>(CapabilityType.TEXT_TO_SPEECH)
            ?: return unsupported(CapabilityType.TEXT_TO_SPEECH)
        return when (action.lowercase()) {
            "speak" -> {
                val text = payload.string("text") ?: return badRequest("Missing 'text'")
                provider.speak(text)
                ServerResponse.json("""{"speaking":true}""")
            }
            "stop" -> {
                provider.stop()
                ServerResponse.json("""{"stopped":true}""")
            }
            else -> unknownAction(action, listOf("speak", "stop"))
        }
    }

    private fun installedApps(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<InstalledAppsCapabilityProvider>(CapabilityType.INSTALLED_APPS)
            ?: return unsupported(CapabilityType.INSTALLED_APPS)
        return when (action.lowercase()) {
            "list", "get" -> {
                val includeSystem = payload.boolean("includeSystem") ?: false
                val apps = provider.getInstalledApps(includeSystem)
                ServerResponse.json(
                    buildJsonObject {
                        put("count", apps.size)
                        put(
                            "apps",
                            buildJsonArray {
                                apps.take(200).forEach { app ->
                                    add(
                                        buildJsonObject {
                                            put("packageName", app.packageName)
                                            put("appName", app.appName)
                                            put("isSystem", app.isSystemApp)
                                            put("version", app.versionName)
                                        }
                                    )
                                }
                            }
                        )
                    }.toString()
                )
            }
            "launch" -> {
                val pkg = payload.string("packageName") ?: return badRequest("Missing 'packageName'")
                val ok = provider.launchApp(pkg)
                ServerResponse.json(buildJsonObject { put("launched", ok) }.toString())
            }
            else -> unknownAction(action, listOf("list", "launch"))
        }
    }

    private fun systemSettings(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<SystemSettingsCapabilityProvider>(CapabilityType.SYSTEM_SETTINGS)
            ?: return unsupported(CapabilityType.SYSTEM_SETTINGS)
        val key = payload.string("key") ?: return badRequest("Missing 'key'")
        return when (action.lowercase()) {
            "get" -> ServerResponse.json(
                buildJsonObject { put("value", provider.getSystemSetting(key) ?: "") }.toString()
            )
            "getsecure" -> ServerResponse.json(
                buildJsonObject { put("value", provider.getSecureSetting(key) ?: "") }.toString()
            )
            else -> unknownAction(action, listOf("get", "getsecure"))
        }
    }

    private fun flashlight(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<FlashlightCapabilityProvider>(CapabilityType.FLASHLIGHT)
            ?: return unsupported(CapabilityType.FLASHLIGHT)
        return when (action.lowercase()) {
            "on" -> ServerResponse.json(buildJsonObject { put("on", provider.setTorch(true)) }.toString())
            "off" -> ServerResponse.json(buildJsonObject { put("on", provider.setTorch(false)) }.toString())
            "toggle" -> ServerResponse.json(buildJsonObject { put("on", provider.toggleTorch()) }.toString())
            "state" -> ServerResponse.json(
                buildJsonObject { put("on", provider.isTorchOn.value) }.toString()
            )
            else -> unknownAction(action, listOf("on", "off", "toggle", "state"))
        }
    }

    private fun display(): ServerResponse {
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

    private fun mediaLibrary(action: String, payload: JsonObject): ServerResponse {
        val provider = provider<MediaLibraryCapabilityProvider>(CapabilityType.MEDIA_LIBRARY)
            ?: return unsupported(CapabilityType.MEDIA_LIBRARY)
        if (!action.equals("recent", ignoreCase = true) && !action.equals("get", ignoreCase = true)) {
            return unknownAction(action, listOf("recent"))
        }
        val limit = payload.int("limit") ?: 10
        val items = provider.getRecentImages(limit)
        return ServerResponse.json(
            buildJsonObject {
                put("count", items.size)
                put(
                    "items",
                    buildJsonArray {
                        items.forEach { m ->
                            add(
                                buildJsonObject {
                                    put("id", m.id)
                                    put("title", m.title)
                                    put("mimeType", m.mimeType)
                                    put("sizeBytes", m.sizeBytes)
                                }
                            )
                        }
                    }
                )
            }.toString()
        )
    }

    private fun deviceInfo(): ServerResponse {
        val provider = provider<DeviceInfoCapabilityProvider>(CapabilityType.DEVICE_INFO)
            ?: return unsupported(CapabilityType.DEVICE_INFO)
        val info = provider.getDeviceInfo()
        return ServerResponse.json(
            buildJsonObject {
                put("manufacturer", info.manufacturer)
                put("model", info.model)
                put("androidVersion", info.androidVersion)
                put("sdkInt", info.sdkInt)
                put("cpuCores", info.cpuCores)
                put("totalRamMb", info.totalRamMb)
                put("availRamMb", info.availRamMb)
            }.toString()
        )
    }

    private fun sensors(): ServerResponse {
        val provider = provider<SensorsCapabilityProvider>(CapabilityType.SENSORS)
            ?: return unsupported(CapabilityType.SENSORS)
        val snapshot = provider.sensorData.value
        return ServerResponse.json(
            buildJsonObject {
                put("accelX", snapshot.accelX)
                put("accelY", snapshot.accelY)
                put("accelZ", snapshot.accelZ)
                put("gyroX", snapshot.gyroX)
                put("gyroY", snapshot.gyroY)
                put("gyroZ", snapshot.gyroZ)
                put("lightLux", snapshot.lightLux)
                put("pressureHpa", snapshot.pressureHpa)
            }.toString()
        )
    }

    private fun wifi(action: String): ServerResponse {
        val provider = provider<WifiCapabilityProvider>(CapabilityType.WIFI)
            ?: return unsupported(CapabilityType.WIFI)
        return when (action.lowercase()) {
            "state", "get" -> ServerResponse.json(
                buildJsonObject {
                    put("enabled", provider.isWifiEnabled)
                    put("peers", provider.p2pPeers.value.size)
                }.toString()
            )
            "peers", "discover" -> {
                provider.discoverWifiDirectPeers()
                ServerResponse.json(buildJsonObject { put("peers", provider.p2pPeers.value.size) }.toString())
            }
            else -> unknownAction(action, listOf("state", "peers"))
        }
    }

    private fun connectivity(): ServerResponse {
        val provider = provider<ConnectivityCapabilityProvider>(CapabilityType.CONNECTIVITY)
            ?: return unsupported(CapabilityType.CONNECTIVITY)
        val t = provider.networkState.value
        return ServerResponse.json(
            buildJsonObject {
                put("isConnected", t.isConnected)
                put("isWifi", t.isWifi)
                put("isCellular", t.isCellular)
                put("isMetered", t.isMetered)
                put("ssid", t.wifiSsid)
                put("ipAddress", t.wifiIpAddress)
                put("linkSpeedMbps", t.wifiLinkSpeedMbps)
                put("frequencyMHz", t.wifiFrequencyMhz)
                put("downstreamKbps", t.downstreamBandwidthKbps)
            }.toString()
        )
    }

    private fun networkUsage(): ServerResponse {
        val provider = provider<NetworkUsageCapabilityProvider>(CapabilityType.NETWORK_USAGE)
            ?: return unsupported(CapabilityType.NETWORK_USAGE)
        val m = provider.getTrafficMetrics()
        return ServerResponse.json(
            buildJsonObject {
                put("mobileRxBytes", m.mobileRxBytes)
                put("mobileTxBytes", m.mobileTxBytes)
                put("totalRxBytes", m.totalRxBytes)
                put("totalTxBytes", m.totalTxBytes)
            }.toString()
        )
    }

    // ---- helpers -------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : com.personal.base.core.capability.CapabilityProvider> provider(
        type: CapabilityType
    ): T? = registry.getProvider(type) as? T

    private fun unsupported(type: CapabilityType) = ServerResponse(
        409, "application/json",
        """{"error":"Capability '${type.name}' has no provider on this device"}"""
    )

    private fun unknownAction(action: String, supported: List<String>) = ServerResponse(
        400, "application/json",
        """{"error":"Unknown action '$action'","supported":${supported.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}"""
    )

    private fun badRequest(reason: String) =
        ServerResponse(400, "application/json", """{"error":"$reason"}""")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    companion object {
        /**
         * The actions a capability genuinely supports, so the catalogue can advertise them and a
         * caller can discover what is possible without guessing.
         */
        fun actionsFor(type: CapabilityType): List<String> = when (type) {
            CapabilityType.DEVICE_ACTIONS -> listOf("haptic", "torch")
            CapabilityType.VIBRATION_HAPTICS -> listOf("pulse")
            CapabilityType.NOTIFICATIONS -> listOf("post")
            CapabilityType.CLIPBOARD -> listOf("read", "write")
            CapabilityType.BATTERY_POWER -> listOf("state")
            CapabilityType.CAMERA -> listOf("cameras", "capture", "start", "stop")
            CapabilityType.MICROPHONE -> listOf("record")
            CapabilityType.QR_BARCODE -> listOf("generate", "decode")
            CapabilityType.SCREEN_CAPTURE -> listOf("state")
            CapabilityType.TEXT_TO_SPEECH -> listOf("speak", "stop")
            CapabilityType.INSTALLED_APPS -> listOf("list", "launch")
            CapabilityType.SYSTEM_SETTINGS -> listOf("get", "getsecure")
            CapabilityType.FLASHLIGHT -> listOf("on", "off", "toggle", "state")
            CapabilityType.DISPLAY -> listOf("get")
            CapabilityType.MEDIA_LIBRARY -> listOf("recent")
            CapabilityType.DEVICE_INFO -> listOf("get")
            CapabilityType.SENSORS -> listOf("get")
            CapabilityType.CONNECTIVITY -> listOf("get")
            CapabilityType.WIFI -> listOf("state", "peers")
            CapabilityType.NETWORK_USAGE -> listOf("get")
            else -> emptyList()
        }
    }
}
