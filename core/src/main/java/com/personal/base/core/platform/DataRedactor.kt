package com.personal.base.core.platform

/**
 * Strips secrets, auth tokens, passwords, and PII from logs and network payloads.
 */
class DataRedactor {
    private val patterns = listOf(
        Regex("(?i)token[\"':\\s=]+([a-zA-Z0-9_-]{8,})") to "token=***REDACTED***",
        Regex("(?i)secret[\"':\\s=]+([a-zA-Z0-9_-]{8,})") to "secret=***REDACTED***",
        Regex("(?i)password[\"':\\s=]+([^\\s,\"'}]+)") to "password=***REDACTED***",
        Regex("\\b[A-Fa-f0-9]{2}(:[A-Fa-f0-9]{2}){5}\\b") to "XX:XX:XX:XX:XX:XX" // MAC Address
    )

    fun redact(input: String): String {
        var result = input
        for ((regex, replacement) in patterns) {
            result = regex.replace(result, replacement)
        }
        return result
    }
}
