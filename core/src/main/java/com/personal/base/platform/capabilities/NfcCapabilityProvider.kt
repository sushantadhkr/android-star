package com.personal.base.platform.capabilities

import android.content.Context
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 2. NfcCapabilityProvider: tag read/write and tap-to-pair protocols.
 */
class NfcCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.NFC

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    val isNfcSupported: Boolean = nfcAdapter != null
    val isNfcEnabled: Boolean = nfcAdapter?.isEnabled == true

    fun createPairingRecord(peerId: String, pin: String): NdefMessage {
        val payload = "androidstar://pair?peerId=$peerId&pin=$pin".toByteArray(Charsets.UTF_8)
        val record = NdefRecord.createUri("androidstar://pair?peerId=$peerId&pin=$pin")
        logger.i("NfcCapability", "Created tap-to-pair NFC NdefMessage for $peerId")
        return NdefMessage(arrayOf(record))
    }

    override fun checkStatus(): CapabilityStatus {
        return when {
            nfcAdapter == null -> CapabilityStatus.Unavailable("NFC hardware not present")
            !nfcAdapter.isEnabled -> CapabilityStatus.Unavailable("NFC disabled in settings")
            else -> CapabilityStatus.Available
        }
    }

    override fun release() {}
}
