package com.personal.base.core.runtime

import android.content.Intent
import android.net.Uri
import android.os.Build
import com.personal.base.core.logging.PlatformLogger

sealed interface SharedContent {
    data class Text(val text: String) : SharedContent
    data class SingleUri(val uri: Uri, val mimeType: String?) : SharedContent
    data class MultipleUris(val uris: List<Uri>) : SharedContent
}

/**
 * Handles inbound share-sheet intakes (text, URLs, single file, multiple files).
 */
class ShareTargetIntake(
    private val logger: PlatformLogger
) {
    fun parseSharedIntent(intent: Intent): SharedContent? {
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text != null) {
                logger.i("ShareTargetIntake", "Received shared text: ${text.take(40)}...")
                return SharedContent.Text(text)
            }

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                logger.i("ShareTargetIntake", "Received shared file URI: $uri")
                return SharedContent.SingleUri(uri, type)
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!uris.isNullOrEmpty()) {
                logger.i("ShareTargetIntake", "Received ${uris.size} shared stream URIs.")
                return SharedContent.MultipleUris(uris)
            }
        }
        return null
    }
}
