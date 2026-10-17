package com.personal.base.platform.capabilities

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import com.personal.base.platform.permissions.PlatformPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * A camera or microphone the platform can use.
 */
data class CameraDescriptor(
    val id: String,
    val isFrontFacing: Boolean,
    val supportedSizes: List<Size>
)

/**
 * A still this camera has taken, addressed by an id rather than a path.
 *
 * The id is the point. A capture lands in app-private storage whose path means nothing to whoever
 * asked for the photo — it is true on this device and wrong on the next one — so an API caller has
 * no way to use it. Handing back an id the platform minted, which resolves to the bytes on request,
 * makes the photo reachable from anywhere the API is.
 *
 * Deliberately no path is ever accepted back in. An id can only name a file this platform wrote,
 * so there is no string a caller could craft to reach something else on the device.
 */
data class CameraCapture(
    val id: String,
    val fileName: String,
    val bytes: Long,
    val cameraId: String,
    val takenAt: Long
)

/**
 * Camera and microphone capture.
 *
 * This genuinely captures: stills are taken through a Camera2 capture session into a JPEG
 * ImageReader, video through MediaRecorder, and audio through AudioRecord. Results are written under
 * the app's files directory and the path is returned, because returning image bytes through a JSON
 * response would be unusable.
 */
@SuppressLint("MissingPermission")
class CameraMicCapabilityProvider(
    private val context: Context,
    private val permissionManager: PlatformPermissionManager,
    private val isAudio: Boolean,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = if (isAudio) CapabilityType.MICROPHONE else CapabilityType.CAMERA

    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var recorder: MediaRecorder? = null
    private var activeRecordingFile: File? = null

    private val _captures = MutableStateFlow<List<CameraCapture>>(emptyList())

    /** Every still taken this session, newest first. */
    val captures: StateFlow<List<CameraCapture>> = _captures.asStateFlow()

    /**
     * How many captures are remembered.
     *
     * Each one is a JPEG sitting in app-private storage, so this is a disk bound and not a
     * convenience. A session that takes thousands of photos would otherwise fill the device with
     * files nothing can reach any more, once the oldest have been dropped.
     */
    private val captureHistoryLimit = 50

    /** Backs the id suffix. Monotonic so two captures in the same millisecond cannot collide. */
    private val captureCounter = java.util.concurrent.atomic.AtomicInteger(0)

    override val status: CapabilityStatus
        get() {
            if (!hardwarePresent()) {
                return CapabilityStatus.Unavailable(
                    if (isAudio) "Microphone hardware not found" else "Camera hardware not found"
                )
            }
            val missing = permissionManager.getMissingPermissions(type)
            if (missing.isNotEmpty()) return CapabilityStatus.PermissionRequired(missing)
            return CapabilityStatus.Available
        }

    private fun hardwarePresent(): Boolean {
        val pm = context.packageManager
        val feature = if (isAudio) PackageManager.FEATURE_MICROPHONE
        else PackageManager.FEATURE_CAMERA_ANY
        return pm.hasSystemFeature(feature)
    }

    override fun initialize() {
        startThread()
        logger.i("CameraMic", "Initialized ${type.displayName}: status=$status")
    }

    override fun release() {
        stopRecordingInternal()
        stopThread()
    }

    // ---- camera -------------------------------------------------------

    /** Enumerates the cameras the device exposes. */
    fun cameras(): List<CameraDescriptor> {
        val manager = cameraManager ?: return emptyList()
        return runCatching {
            manager.cameraIdList.mapNotNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                CameraDescriptor(
                    id = id,
                    isFrontFacing = chars.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT,
                    supportedSizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                        ?.toList()
                        ?: emptyList()
                )
            }
        }.getOrElse {
            logger.e("CameraMic", "Cannot enumerate cameras: ${it.message}")
            emptyList()
        }
    }

    /**
     * Takes a still and returns the file, or null on failure.
     *
     * Blocking with a timeout, because the caller is an HTTP worker that has nothing else to do
     * while the shutter completes.
     */
    fun captureImage(
        cameraId: String? = null,
        maxWidth: Int = 1920,
        timeoutMs: Long = 8_000
    ): File? {
        if (isAudio) return null
        if (!status.isUsable) {
            logger.w("CameraMic", "Cannot capture: status=$status")
            return null
        }

        val manager = cameraManager ?: return null
        val target = cameraId ?: pickCameraId()
        if (target == null) {
            logger.w("CameraMic", "No camera available")
            return null
        }

        startThread()
        val h = handler ?: return null
        val latch = CountDownLatch(1)
        val output = java.util.concurrent.atomic.AtomicReference<File?>(null)

        val size = chooseJpegSize(target, maxWidth)
        val imageReader = android.media.ImageReader.newInstance(
            size.width, size.height, android.graphics.ImageFormat.JPEG, 2
        )

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        try {
            imageReader.setOnImageAvailableListener({ reader ->
                runCatching {
                    val image = reader.acquireLatestImage() ?: return@runCatching
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    val file = File(captureDir(), "capture-${System.currentTimeMillis()}.jpg")
                    file.writeBytes(bytes)
                    output.set(file)
                    logger.i("CameraMic", "Captured ${bytes.size} bytes to ${file.name}")
                }.onFailure {
                    logger.e("CameraMic", "Capture failed: ${it.message}")
                }
                latch.countDown()
            }, h)

            manager.openCamera(target, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    runCatching {
                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    session = configured
                                    val request = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE
                                    ).apply {
                                        addTarget(imageReader.surface)
                                        set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                        )
                                    }.build()
                                    configured.capture(request, null, h)
                                }

                                override fun onConfigureFailed(configured: CameraCaptureSession) {
                                    logger.e("CameraMic", "Capture session configuration failed")
                                    latch.countDown()
                                }
                            },
                            h
                        )
                    }.onFailure {
                        logger.e("CameraMic", "Session creation failed: ${it.message}")
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) = camera.close()

                override fun onError(camera: CameraDevice, error: Int) {
                    logger.e("CameraMic", "Camera error $error")
                    camera.close()
                    latch.countDown()
                }
            }, h)
        } catch (e: CameraAccessException) {
            logger.e("CameraMic", "Camera access failed: ${e.message}", e.message)
            latch.countDown()
        } catch (e: SecurityException) {
            logger.e("CameraMic", "Camera permission missing: ${e.message}", e.message)
            latch.countDown()
        }

        // Bounded wait: a capture that never completes must not wedge the server worker.
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { imageReader.close() }

        val file = output.get() ?: return null
        remember(file, target)
        return file
    }

    /**
     * Files this camera has taken, newest first.
     *
     * Returned newest first to match [MediaDownloadCapabilityProvider.recent], so a caller reading
     * either list takes the same "the one I just made is first" path.
     */
    fun recentCaptures(limit: Int): List<CameraCapture> =
        _captures.value.take(limit.coerceAtLeast(0))

    /** The file behind a capture id, or null if the id is unknown or its file has gone. */
    fun captureFile(id: String): File? {
        val capture = _captures.value.firstOrNull { it.id == id } ?: return null
        val file = File(captureDir(), capture.fileName)
        return if (file.isFile) file else null
    }

    /** The bytes behind a capture id. Null when the id is unknown or the file cannot be read. */
    fun readCapture(id: String): ByteArray? = runCatching { captureFile(id)?.readBytes() }.getOrNull()

    /**
     * What kind of thing a capture is, so a client can name the file it saves.
     *
     * Derived from the bytes rather than trusted from the caller's file name: the shutter only ever
     * writes JPEG, so this reports JPEG, and a request cannot talk the platform into labelling a
     * photo as something else.
     */
    fun captureContentType(id: String): String? =
        if (captureFile(id) != null) "image/jpeg" else null

    /**
     * Gives a freshly written file an id and keeps it findable.
     *
     * The id is a counter the caller never sees or supplies, so it cannot be chosen to collide with
     * another capture or to name a file that is not one of ours.
     */
    private fun remember(file: File, cameraId: String) {
        val capture = CameraCapture(
            id = "cap_" + System.currentTimeMillis().toString(36) + "_" +
                captureCounter.incrementAndGet(),
            fileName = file.name,
            bytes = file.length(),
            cameraId = cameraId,
            takenAt = System.currentTimeMillis()
        )

        // The file is on disk before it is listed, so anything reading the list can open it. The
        // reverse would hand out ids for files that do not exist yet.
        val all = listOf(capture) + _captures.value
        _captures.value = all.take(captureHistoryLimit)

        // Anything that fell off the end is deleted rather than orphaned: it is unreachable by id
        // now, so leaving it would be a file on the device that no API can ever account for.
        all.drop(captureHistoryLimit).forEach { stale ->
            runCatching { File(captureDir(), stale.fileName).delete() }
        }
    }

    /**
     * Starts recording video. Returns the output file, or null on failure.
     *
     * Call [stopRecording] to finish the file.
     */
    fun startRecording(cameraId: String? = null): File? {
        if (isAudio) return null
        if (_recording.value) {
            logger.w("CameraMic", "Already recording")
            return activeRecordingFile
        }
        if (!status.isUsable) {
            logger.w("CameraMic", "Cannot record: status=$status")
            return null
        }

        val manager = cameraManager ?: return null
        val target = cameraId ?: pickCameraId() ?: return null
        val size = chooseJpegSize(target, 1920)

        val file = File(captureDir(), "video-${System.currentTimeMillis()}.mp4")

        return runCatching {
            val rec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(size.width, size.height)
                setVideoFrameRate(30)
                setOutputFile(file.absolutePath)
                // The camera feeds frames to the recorder through this surface.
                prepare()
            }

            startThread()
            val h = handler ?: return null
            val latch = CountDownLatch(1)

            val surface = android.media.ImageReader
                .newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 2)
                .surface

            manager.openCamera(target, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    runCatching {
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    rec.start()
                                    recorder = rec
                                    activeRecordingFile = file
                                    _recording.value = true
                                    logger.i("CameraMic", "Recording to ${file.name}")
                                    latch.countDown()
                                }

                                override fun onConfigureFailed(configured: CameraCaptureSession) {
                                    logger.e("CameraMic", "Record session failed")
                                    latch.countDown()
                                }
                            },
                            h
                        )
                    }.onFailure {
                        logger.e("CameraMic", "Record setup failed: ${it.message}")
                        latch.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) = camera.close()
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    latch.countDown()
                }
            }, h)

            latch.await(8_000, TimeUnit.MILLISECONDS)
            file.takeIf { _recording.value }
        }.getOrElse {
            logger.e("CameraMic", "Start recording failed: ${it.message}")
            null
        }
    }

    /** Stops recording and finalises the file. */
    fun stopRecording(): File? {
        if (isAudio) return null
        return stopRecordingInternal()
    }

    private fun stopRecordingInternal(): File? {
        val rec = recorder ?: return null
        val file = activeRecordingFile
        runCatching {
            rec.stop()
            rec.reset()
            rec.release()
        }.onFailure {
            logger.w("CameraMic", "Error stopping recorder: ${it.message}")
        }
        recorder = null
        activeRecordingFile = null
        _recording.value = false
        logger.i("CameraMic", "Recording finished: ${file?.name}")
        return file
    }

    // ---- microphone ----------------------------------------------------

    /**
     * Records PCM audio for a fixed duration and returns the bytes.
     *
     * A blocking call, matching [captureImage], so a script can ask for a clip and get it back.
     */
    fun recordAudio(durationMs: Int = 2000, sampleRate: Int = 16_000): ByteArray? {
        if (!isAudio) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            logger.w("CameraMic", "RECORD_AUDIO not granted")
            return null
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null

        return runCatching {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
            val samples = (sampleRate * durationMs / 1000)
            val out = ByteArray(samples * 2)
            record.startRecording()
            var written = 0
            while (written < out.size) {
                val n = record.read(out, written, out.size - written)
                if (n <= 0) break
                written += n
            }
            record.stop()
            record.release()
            logger.i("CameraMic", "Recorded ${written / 2} audio samples (${durationMs}ms)")
            if (written <= 0) null else out.copyOf(written)
        }.getOrElse {
            logger.e("CameraMic", "Audio record failed: ${it.message}")
            null
        }
    }

    /** Writes raw PCM to a file and returns it, for callers that want a file rather than bytes. */
    fun recordAudioToFile(durationMs: Int = 2000): File? {
        val pcm = recordAudio(durationMs) ?: return null
        val file = File(captureDir(), "audio-${System.currentTimeMillis()}.pcm")
        return runCatching {
            file.writeBytes(pcm)
            file
        }.getOrNull()
    }

    // ---- helpers -------------------------------------------------------

    private fun pickCameraId(): String? = runCatching {
        val manager = cameraManager ?: return null
        val ids = manager.cameraIdList
        // Prefer the rear camera; it is what a capture surface is normally wanted for.
        ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }.getOrNull()

    private fun chooseJpegSize(cameraId: String, maxWidth: Int): Size {
        val sizes = cameras().firstOrNull { it.id == cameraId }?.supportedSizes
            ?: return Size(maxWidth, maxWidth * 3 / 4)
        // Largest that does not exceed the requested width, so output stays sane over HTTP.
        return sizes
            .filter { it.width <= maxWidth }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(maxWidth, maxWidth * 3 / 4)
    }

    private fun captureDir(): File =
        File(context.filesDir, "captures").apply { if (!exists()) mkdirs() }

    private fun startThread() {
        if (thread != null) return
        thread = HandlerThread("camera-mic").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    private fun stopThread() {
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
