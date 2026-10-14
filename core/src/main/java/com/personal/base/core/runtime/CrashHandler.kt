package com.personal.base.core.runtime

import android.content.Context
import com.personal.base.core.logging.PlatformLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught exception interceptor capturing crash dumps to disk
 * and facilitating graceful state salvage and recovery.
 */
class CrashHandler(
    private val context: Context,
    private val logger: PlatformLogger
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
        logger.i("CrashHandler", "Installed global uncaught exception interceptor.")
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val crashFile = File(context.filesDir, "crash_$timestamp.log")
            crashFile.writeText(
                """
                === ANDROID STAR CRASH DUMP ===
                Time: ${Date()}
                Thread: ${thread.name} [ID: ${thread.id}]
                Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})
                Exception: ${throwable.javaClass.name}: ${throwable.message}
                Stacktrace:
                $stackTrace
                ===============================
                """.trimIndent()
            )
            logger.e("CrashHandler", "Fatal crash intercepted: ${throwable.message}", stackTrace)
        } catch (_: Exception) {}

        defaultHandler?.uncaughtException(thread, throwable)
    }

    fun getCrashLogs(): List<File> {
        return context.filesDir.listFiles { _, name -> name.startsWith("crash_") && name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
}
