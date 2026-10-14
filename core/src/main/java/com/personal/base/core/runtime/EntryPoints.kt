package com.personal.base.core.runtime

import android.content.Context
import android.net.Uri
import com.personal.base.core.logging.PlatformLogger

/**
 * EntryPoints router for deep links (personalbase://) and app shortcuts.
 *
 * Lives in core: routing is shell-agnostic and Apps may need to register handlers for their own
 * deep links. Nothing here depends on the shell.
 */
class EntryPoints(
    private val context: Context,
    private val logger: PlatformLogger
) {
    fun handleDeepLink(uri: Uri): Boolean {
        logger.i("EntryPoints", "Processing deep link: $uri")
        if (uri.scheme == "personalbase") {
            val host = uri.host
            val action = uri.getQueryParameter("action")
            logger.i("EntryPoints", "Deep link host: $host, action: $action")
            return true
        }
        return false
    }
}