package com.personal.base.platform.capabilities

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int
)

/**
 * 16. UsbCapabilityProvider: USB host and accessory device discovery and permissions.
 */
class UsbCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.USB

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    fun getConnectedUsbDevices(): List<UsbDeviceInfo> {
        val list = mutableListOf<UsbDeviceInfo>()
        val deviceMap = usbManager?.deviceList ?: emptyMap()
        for ((_, dev) in deviceMap) {
            list.add(
                UsbDeviceInfo(
                    deviceName = dev.deviceName,
                    vendorId = dev.vendorId,
                    productId = dev.productId,
                    deviceClass = dev.deviceClass
                )
            )
        }
        logger.i("UsbCapability", "Found ${list.size} connected USB devices.")
        return list
    }

    override fun checkStatus(): CapabilityStatus {
        return if (usbManager != null) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("USB manager unavailable")
    }

    override fun release() {}
}
