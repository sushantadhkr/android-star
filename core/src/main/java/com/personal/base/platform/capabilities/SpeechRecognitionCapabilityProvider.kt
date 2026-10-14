package com.personal.base.platform.capabilities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 10. SpeechRecognitionCapabilityProvider: on-device voice to text dictation.
 */
class SpeechRecognitionCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.SPEECH_RECOGNITION

    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    val isRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(onResult: (String) -> Unit) {
        if (!isRecognitionAvailable) return
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { _isRecognizing.value = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { _isRecognizing.value = false }
                override fun onError(error: Int) {
                    _isRecognizing.value = false
                    logger.w("SpeechRecognition", "Speech recognizer error code: $error")
                }
                override fun onResults(results: Bundle?) {
                    _isRecognizing.value = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    _lastTranscript.value = text
                    logger.i("SpeechRecognition", "Recognized speech: '$text'")
                    onResult(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { _lastTranscript.value = it }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        } catch (e: Exception) {
            logger.e("SpeechRecognition", "Failed to start listening: ${e.message}", e.message)
        }
    }

    override fun checkStatus(): CapabilityStatus {
        return if (isRecognitionAvailable) CapabilityStatus.Available
        else CapabilityStatus.Unavailable("Speech recognition not supported on this device")
    }

    override fun release() {}
}
