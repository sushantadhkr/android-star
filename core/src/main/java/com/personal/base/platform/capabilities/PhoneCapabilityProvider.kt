package com.personal.base.platform.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/** What the phone line looks like, gathered only from permissions this app actually holds. */
data class PhoneStatus(
    /** Whether this device has telephony hardware at all. Needs no permission. */
    val hasTelephony: Boolean,
    /** How many SIM slots the hardware reports. Needs no permission. */
    val phoneCount: Int,
    /** True only when READ_PHONE_STATE is held right now. */
    val permissionGranted: Boolean,
    val networkOperator: String,
    val simOperator: String,
    val simCountryIso: String,
    val phoneNumber: String,
    val simState: String,
    val networkType: String,
    /** Which fields are missing purely because of the permission, so the answer is self-explaining. */
    val withheld: List<String>
)

/**
 * Telephony state.
 *
 * Nearly everything worth reading here sits behind READ_PHONE_STATE, which is a runtime permission
 * on Android 16 and is not granted by default. So this provider separates the two halves honestly:
 * hardware facts that need no permission are always reported, and permission-gated fields come back
 * named in `withheld` rather than as empty strings pretending to be answers.
 *
 * The phone number is deliberately never claimed even when granted — on most carriers it comes back
 * empty regardless, and a blank field presented as "your number" is a worse answer than none.
 */
class PhoneCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.PHONE

    private val telephony: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val isGranted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun status(): PhoneStatus {
        val tm = telephony
        val hasTelephony = tm != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        // These two are readable without any permission, so they are answered either way.
        val count = runCatching { tm?.phoneCount ?: 0 }.getOrDefault(0)

        if (!isGranted) {
            logger.d("Phone", "READ_PHONE_STATE not granted; reporting hardware only")
            return PhoneStatus(
                hasTelephony = hasTelephony,
                phoneCount = count,
                permissionGranted = false,
                networkOperator = "",
                simOperator = "",
                simCountryIso = "",
                phoneNumber = "",
                simState = "",
                networkType = "",
                withheld = listOf(
                    "networkOperator", "simOperator", "simCountryIso", "phoneNumber",
                    "simState", "networkType"
                )
            )
        }

        val withheld = mutableListOf<String>()

        fun <T> read(name: String, read: () -> T?): T? {
            val value = runCatching { read() }.getOrNull()
            if (value == null || (value is String && value.isBlank())) withheld.add(name)
            return value
        }

        return PhoneStatus(
            hasTelephony = hasTelephony,
            phoneCount = count,
            permissionGranted = true,
            networkOperator = read("networkOperator") { tm?.networkOperator } ?: "",
            simOperator = read("simOperator") { tm?.simOperator } ?: "",
            simCountryIso = read("simCountryIso") { tm?.simCountryIso } ?: "",
            phoneNumber = "",
            simState = read("simState") {
                // int -> name, so a SIM that is absent reads as absent rather than as "0".
                when (runCatching { tm?.simState }.getOrNull()) {
                    TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                    TelephonyManager.SIM_STATE_READY -> "READY"
                    else -> "UNKNOWN"
                }
            } ?: "UNKNOWN",
            networkType = read("networkType") {
                runCatching { tm?.dataNetworkType }.getOrNull()?.let { type ->
                    when (type) {
                        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                        TelephonyManager.NETWORK_TYPE_NR -> "NR"
                        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                        else -> type.toString()
                    }
                }
            } ?: "",
            // Even with the permission held, carriers commonly return nothing here. Claiming a
            // number this provider cannot actually see would be the exact kind of fake success
            // this platform does not ship, so the field is never populated.
            withheld = withheld + "phoneNumber"
        )
    }

    override fun checkStatus(): CapabilityStatus = when {
        telephony == null -> CapabilityStatus.Unavailable("No telephony service on this device")
        isGranted -> CapabilityStatus.Available
        // Hardware facts still answer without the permission, so this is Partial rather than
        // PermissionRequired: refusing the whole capability would hide what genuinely works.
        else -> CapabilityStatus.Partial(
            whatWorks = "Hardware presence and SIM slot count",
            whatDoesNot = "Operator, SIM and network details, which need READ_PHONE_STATE"
        )
    }

    override fun release() {}
}