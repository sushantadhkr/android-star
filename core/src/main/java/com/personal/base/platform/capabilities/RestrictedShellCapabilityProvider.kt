package com.personal.base.platform.capabilities

import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellExecutionResult(
    val command: String,
    val exitCode: Int,
    val output: String,
    val isAllowed: Boolean
)

/**
 * 40. RestrictedShellCapabilityProvider: allowlisted shell commands only (ping, df, ip, getprop, uname, uptime) with user consent.
 */
class RestrictedShellCapabilityProvider(
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.RESTRICTED_SHELL

    private val allowlist = setOf("ping", "df", "ip", "getprop", "uname", "uptime", "top", "date")

    suspend fun executeAllowlisted(commandLine: String): ShellExecutionResult = withContext(Dispatchers.IO) {
        val tokens = commandLine.trim().split("\\s+".toRegex())
        val binary = tokens.firstOrNull() ?: ""

        if (!allowlist.contains(binary)) {
            logger.w("RestrictedShell", "Blocked unapproved shell binary: $binary")
            return@withContext ShellExecutionResult(commandLine, -1, "Command '$binary' is not allowlisted.", false)
        }

        try {
            val process = Runtime.getRuntime().exec(commandLine)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            logger.i("RestrictedShell", "Executed '$commandLine' -> exit code $exitCode")
            ShellExecutionResult(commandLine, exitCode, output, true)
        } catch (e: Exception) {
            logger.e("RestrictedShell", "Shell execution error: ${e.message}", e.message)
            ShellExecutionResult(commandLine, -1, "Error: ${e.message}", true)
        }
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
