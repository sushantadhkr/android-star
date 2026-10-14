package com.personal.base.core.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val details: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

/**
 * Structured, persistent-ready platform logger with in-memory ring buffer and live flow updates.
 */
class PlatformLogger(private val maxEntries: Int = 500) {

    private val entries = ConcurrentLinkedDeque<LogEntry>()
    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 64)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    fun log(level: LogLevel, tag: String, message: String, details: String? = null) {
        val entry = LogEntry(level = level, tag = tag, message = message, details = details)
        
        // Also log to Android Logcat if running on Android runtime
        try {
            when (level) {
                LogLevel.DEBUG -> Log.d(tag, "$message ${details ?: ""}")
                LogLevel.INFO -> Log.i(tag, "$message ${details ?: ""}")
                LogLevel.WARN -> Log.w(tag, "$message ${details ?: ""}")
                LogLevel.ERROR -> Log.e(tag, "$message ${details ?: ""}")
            }
        } catch (_: Throwable) {
            // Running in JVM unit test where android.util.Log is unmocked
            println("[$level] [$tag] $message ${details ?: ""}")
        }

        entries.addLast(entry)
        while (entries.size > maxEntries) {
            entries.pollFirst()
        }
        _logFlow.tryEmit(entry)
    }

    fun d(tag: String, message: String, details: String? = null) = log(LogLevel.DEBUG, tag, message, details)
    fun i(tag: String, message: String, details: String? = null) = log(LogLevel.INFO, tag, message, details)
    fun w(tag: String, message: String, details: String? = null) = log(LogLevel.WARN, tag, message, details)
    fun e(tag: String, message: String, details: String? = null) = log(LogLevel.ERROR, tag, message, details)

    fun getEntries(): List<LogEntry> = entries.toList()

    fun clear() {
        entries.clear()
    }
}
