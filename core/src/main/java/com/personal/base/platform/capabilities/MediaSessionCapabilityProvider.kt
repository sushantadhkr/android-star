package com.personal.base.platform.capabilities

import android.content.Context
import android.media.session.MediaSessionManager
import android.view.KeyEvent
import com.personal.base.core.capability.CapabilityProvider
import com.personal.base.core.capability.CapabilityStatus
import com.personal.base.core.capability.CapabilityType
import com.personal.base.core.logging.PlatformLogger

/**
 * 7. MediaSessionCapabilityProvider: play/pause/next, media transport controls, and now-playing info.
 */
class MediaSessionCapabilityProvider(
    private val context: Context,
    private val logger: PlatformLogger
) : CapabilityProvider {

    override val type: CapabilityType = CapabilityType.MEDIA_SESSION

    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    fun dispatchMediaKeyEvent(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
        logger.i("MediaSessionCapability", "Dispatched media key code: $keyCode")
    }

    fun playPause() = dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun nextTrack() = dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previousTrack() = dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    override fun checkStatus(): CapabilityStatus = CapabilityStatus.Available
    override fun release() {}
}
