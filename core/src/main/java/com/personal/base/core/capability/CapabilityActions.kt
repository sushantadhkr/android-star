package com.personal.base.core.capability

import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.ServerResponse
import com.personal.base.platform.capabilities.BatteryPowerCapabilityProvider
import com.personal.base.platform.capabilities.ClipboardCapabilityProvider
import com.personal.base.platform.capabilities.ConnectivityCapabilityProvider
import com.personal.base.platform.capabilities.DeviceActionCapabilityProvider
import com.personal.base.platform.capabilities.DeviceInfoCapabilityProvider
import com.personal.base.platform.capabilities.NetworkUsageCapabilityProvider
import com.personal.base.platform.capabilities.NotificationCapabilityProvider
import com.personal.base.platform.capabilities.SensorsCapabilityProvider
import com.personal.base.platform.capabilities.WifiCapabilityProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            CapabilityType.DEVICE_INFO -> listOf("get")
            CapabilityType.SENSORS -> listOf("get")
            CapabilityType.CONNECTIVITY -> listOf("get")
            CapabilityType.WIFI -> listOf("state", "peers")
            CapabilityType.NETWORK_USAGE -> listOf("get")
            else -> emptyList()
        }
    }
}
