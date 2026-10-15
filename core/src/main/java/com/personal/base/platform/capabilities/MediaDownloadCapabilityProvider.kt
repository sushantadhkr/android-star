package com.personal.base.platform.capabilities

import android.content.ContentValues
import android.os.Build
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.core.platform.NotificationActionContract
import com.personal.base.core.platform.NotificationButton
import com.personal.base.core.platform.ProgressNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** One download, and how far it has got. */
data class MediaDownload(
    val id: String,
    val url: String,
    val fileName: String,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val savedPath: String?,
    val error: String?,
    val startedAt: Long
) {
    val percent: Int
        get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
}

/**
 * Fetches media from a URL and puts it in the phone's Downloads folder.
 *
 * This is the platform's own downloader rather than the system [android.app.DownloadManager]
 * because that one reports progress poorly, cannot say where a file ended up, and writes to a
 * destination API that is deprecated since scoped storage. Here the file is written through
 * MediaStore, so it lands in the user's real Downloads folder and shows up in Gallery for images
 * and video — which is what someone asking for "download this to my phone" actually expects.
 *
 * The platform can reach anything the phone can reach, including machines on the local network.
 * That is the same reach the always-on host already has, and the bearer token is what gates it.
 */
class MediaDownloadCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger,
    /** Where files go. Absent in tests and in any build with no user choice, which means Downloads. */
    private val location: com.personal.base.core.platform.DownloadLocationStore? = null
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.DOWNLOAD_MANAGER

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val downloads = ConcurrentHashMap<String, MediaDownload>()
    private val lastProgressAt = ConcurrentHashMap<String, Long>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** Everything this provider has been asked to fetch, newest first. */
    private val _history = MutableStateFlow<List<MediaDownload>>(emptyList())
    val history: StateFlow<List<MediaDownload>> = _history.asStateFlow()

    /** Shared, so an App posting a long job gets the same bar and the same controls. */
    private val notifier = ProgressNotifier(context, logger)

    override fun initialize() {
        logger.i("MediaDownload", "Media download provider ready")
    }

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available

    /**
     * A tile showing what the downloader is doing.
     *
     * Downloads are exactly the sort of thing a home screen is for: something running, something
     * worth glancing at, something to stop. It carries the active count and the overall progress
     * rather than any one file, because a tile showing one job is a tile that is wrong as soon as a
     * second starts.
     */
    override fun widgetSpec(): com.personal.base.core.app.WidgetSpec? {
        val all = _history.value
        if (all.isEmpty()) return null

        val active = all.count { it.status == "downloading" || it.status == "queued" }
        val latest = all.last()

        val status = when {
            active > 0 -> "$active downloading · ${latest.percent}%"
            latest.status == "complete" -> "Saved ${latest.fileName}"
            latest.status == "failed" -> "Failed: ${latest.error ?: "unknown"}"
            latest.status == "cancelled" -> "Cancelled"
            else -> latest.status
        }

        val progress = when {
            active > 0 -> latest.percent
            latest.status == "complete" -> 100
            else -> null
        }

        return com.personal.base.core.app.WidgetSpec(
            id = "downloads",
            title = if (active > 0) "Downloading" else "Downloads",
            subtitle = "${all.size} file${if (all.size == 1) "" else "s"} today",
            status = status,
            icon = com.personal.base.core.app.WidgetSpec.ICON_DOWNLOAD,
            progress = progress
        )
    }

    override fun release() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    fun get(id: String): MediaDownload? = downloads[id]

    fun recent(limit: Int): List<MediaDownload> =
        _history.value.takeLast(limit.coerceAtLeast(0)).reversed()

    /**
     * Starts a download and returns it immediately.
     *
     * Asynchronous on purpose: an HTTP request that blocks until a large file finishes would hold
     * the server's only thread per client, and the caller has no way to cancel it. The returned id
     * is how they follow it.
     */
    fun start(url: String, fileName: String?, mediaType: String?): MediaDownload? {
        // Only http(s). Anything else — file://, content://, an intent scheme — would turn this
        // into a way to read the device's own storage or fire an arbitrary handler.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            logger.w("MediaDownload", "Refusing non-http url: $url")
            return null
        }

        val id = "dl_" + System.currentTimeMillis().toString(36) + "_" +
            (downloads.size % 1000)

        // Sanitised here rather than only at write time, so the name this download reports is the
        // name it actually uses. Reporting "../../etc/passwd" while writing "passwd" would leave the
        // caller believing a file went somewhere it did not.
        val name = sanitise(fileName?.takeIf { it.isNotBlank() } ?: guessFileName(url))

        lastProgressAt[id] = System.currentTimeMillis()
        val record = MediaDownload(
            id = id,
            url = url,
            fileName = name,
            status = "queued",
            bytesDownloaded = 0,
            totalBytes = 0,
            savedPath = null,
            error = null,
            startedAt = System.currentTimeMillis()
        )
        downloads[id] = record
        push()

        show(record)
        jobs[id] = scope.launch { run(record.id, url, name, mediaType) }
        return record
    }

    fun cancel(id: String): Boolean {
        notifier.cancel(id)
        val job = jobs.remove(id) ?: return false
        job.cancel()
        update(id) { it.copy(status = "cancelled", error = "cancelled by the caller") }
        return true
    }

    private suspend fun run(id: String, url: String, fileName: String, mediaType: String?) {
        var connection: HttpURLConnection? = null
        var uri: Uri? = null
        var output: java.io.OutputStream? = null

        try {
            update(id) { it.copy(status = "downloading") }

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("server answered HTTP $code")
            }

            val declared = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            val resolvedType = mediaType
                ?: connection.contentType?.substringBefore(';')?.trim()
                ?: guessMimeType(fileName)

            uri = createDestination(fileName, resolvedType)
            output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("could not open a file to write to")

            var written = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (!currentCoroutineContext().isActive) return
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    // Progress is published periodically rather than per chunk; a 1 KB buffer on a
                    // large file would otherwise churn the state flow thousands of times a second.
                    // Throttled hard. A 64 KB buffer on a large file would otherwise post
                    // thousands of notifications a second, which is its own kind of freeze.
                    if (System.currentTimeMillis() - lastProgressAt[id]!! > PROGRESS_INTERVAL_MS) {
                        lastProgressAt[id] = System.currentTimeMillis()
                        update(id) { it.copy(bytesDownloaded = written, totalBytes = declared) }
                        show(downloads[id]!!)
                    }
                }
                output.flush()
            }

            finish(uri)
            uri = null

            update(id) {
                it.copy(
                    status = "complete",
                    bytesDownloaded = written,
                    totalBytes = if (declared > 0) declared else written,
                    savedPath = pathFor(fileName),
                    error = null
                )
            }
            logger.i("MediaDownload", "Saved $fileName ($written bytes) to Downloads")
            show(downloads[id]!!)
        } catch (e: Exception) {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            val cancelled = e is kotlinx.coroutines.CancellationException
            update(id) {
                it.copy(
                    status = if (cancelled) "cancelled" else "failed",
                    error = e.message ?: e.javaClass.simpleName
                )
            }
            if (!cancelled) logger.e("MediaDownload", "Download of $url failed", e.message)
        } finally {
            connection?.disconnect()
            runCatching { output?.close() }
            jobs.remove(id)
        }
    }

    /** Publishes the row so it stops looking like an abandoned draft. */
    private fun finish(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    private fun createDestination(fileName: String, mimeType: String): Uri {
        val safeName = sanitise(fileName)

        // A folder the user chose wins over the default. Written through the SAF tree rather than
        // a file path, because that is the only way that keeps working under scoped storage.
        val chosen = location?.folder
        if (chosen != null) {
            val created = runCatching {
                android.provider.DocumentsContract.createDocument(
                    context.contentResolver, chosen, mimeType, safeName
                )
            }.getOrNull()
            if (created != null) return created
            // A grant that has been revoked lands here. Falling back keeps the download working
            // rather than failing on something the user cannot see the cause of.
            logger.w("MediaDownload", "Chosen folder refused $safeName; falling back to Downloads")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + MediaDownloadCapabilityProvider.FOLDER
                )
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Downloads folder is not writable")
        }

        @Suppress("DEPRECATION")
        val dir = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("could not create $dir")
        }
        val file = java.io.File(dir, safeName)
        return Uri.fromFile(file)
    }

    private fun pathFor(fileName: String): String =
        location?.folderName?.let { "$it/${sanitise(fileName)}" }
            ?: "Downloads/$FOLDER/${sanitise(fileName)}"

    /**
     * A name from a URL is not a name we can trust on a filesystem.
     *
     * A URL can carry `../`, a null byte, or a 300-character segment, any of which turns a download
     * into something that writes outside the folder it was given.
     */
    private fun sanitise(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace('\u0000', ' ')
            .filter { it.isLetterOrDigit() || it in "._- ()" }
            .trim()
            .ifBlank { "download" }
        return if (base.length <= 120) base else base.takeLast(120)
    }

    private fun guessFileName(url: String): String =
        url.substringBefore('?').substringAfterLast('/').ifBlank { "download.bin" }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "heic" -> "image/$name"
        "mp4", "mkv", "mov", "webm", "avi" -> "video/$name"
        "mp3", "m4a", "wav", "ogg", "flac" -> "audio/$name"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /**
     * Puts the job on screen, with the controls that matter.
     *
     * Cancel and Retry are ordinary notification buttons, so they go through the same capability
     * gate as everything else: switch DOWNLOAD_MANAGER off and neither works. Each carries its own
     * download id, because a Cancel button that did not know which download it belonged to would be
     * worse than no button at all.
     */
    private fun show(d: MediaDownload) {
        val cancel = NotificationButton(
            id = "download-cancel",
            label = "Cancel",
            icon = NotificationActionContract.ICON_DELETE,
            invoke = "DOWNLOAD_MANAGER:cancel",
            params = mapOf("id" to d.id)
        )
        val retry = NotificationButton(
            id = "download-retry",
            label = "Retry",
            icon = NotificationActionContract.ICON_REFRESH,
            invoke = "DOWNLOAD_MANAGER:retry",
            params = mapOf("id" to d.id)
        )

        val known = d.totalBytes > 0
        when (d.status) {
            "queued", "downloading" -> notifier.progress(
                id = d.id,
                title = d.fileName,
                detail = if (known) "${d.percent}% of ${human(d.totalBytes)}" else "Downloading…",
                percent = d.percent,
                totalBytes = d.totalBytes,
                buttons = listOf(cancel)
            )
            "complete" -> notifier.complete(
                d.id, d.fileName, "Saved to ${d.savedPath ?: "Downloads"}", listOf(retry)
            )
            "failed" -> notifier.failed(d.id, d.fileName, d.error ?: "Failed", retry)
            "cancelled" -> notifier.complete(d.id, d.fileName, "Cancelled", listOf(retry))
        }
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    private fun update(id: String, change: (MediaDownload) -> MediaDownload) {
        downloads.computeIfPresent(id) { _, current -> change(current) }
        push()
    }

    private fun push() {
        _history.value = downloads.values.sortedBy { it.startedAt }.toList()
        // The tile is derived state, so it is redrawn whenever the state it describes moves.
        onChanged?.invoke()
    }

    /**
     * Told whenever this provider's state changes, so whatever is showing it can redraw.
     *
     * A capability must not know about widgets to publish one; it only has to say what changed.
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    companion object {
        const val FOLDER = "Android Star"
        const val PROGRESS_INTERVAL_MS = 700L
    }
}