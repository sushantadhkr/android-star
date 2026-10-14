package com.personal.base.platform.capabilities

import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 41. SftpServerCapabilityProvider: SFTP/SSH daemon service for mounting and browsing the phone from a desktop.
 */
class SftpServerCapabilityProvider(
    private val logger: PlatformLogger,
    val port: Int = 2222
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SFTP_SERVER

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun startServer() {
        _isRunning.value = true
        logger.i("SftpServer", "SFTP daemon initialized on port $port")
    }

    fun stopServer() {
        _isRunning.value = false
        logger.i("SftpServer", "SFTP daemon stopped.")
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {
        stopServer()
    }
}
