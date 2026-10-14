package com.personal.base.platform.capabilities

import android.content.Context
import android.speech.tts.TextToSpeech
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import java.util.Locale

/**
 * 9. TextToSpeechCapabilityProvider: on-device speech synthesis and voice announcements.
 */
class TextToSpeechCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider, TextToSpeech.OnInitListener {

    override val type: CapabilityType = CapabilityType.TEXT_TO_SPEECH

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        try {
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            logger.w("TTSCapability", "Failed to construct TextToSpeech: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            isInitialized = true
            logger.i("TTSCapability", "TextToSpeech engine initialized.")
        }
    }

    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (isInitialized) {
            tts?.speak(text, queueMode, null, "android_star_tts_${System.currentTimeMillis()}")
            logger.i("TTSCapability", "Speaking: '$text'")
        }
    }

    fun stop() {
        tts?.stop()
    }

    override fun checkStatus(): CapabilityStatus {
        return if (isInitialized) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("TTS engine initializing or unavailable")
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
