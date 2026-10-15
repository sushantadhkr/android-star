package com.personal.base

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.personal.base.core.ui.theme.PersonalBaseTheme
import com.personal.base.ui.MainPlatformScreen

class MainActivity : ComponentActivity() {

    private val container by lazy {
        (application as PersonalBaseApplication).container
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Opening the app is the one moment Android always permits a foreground service to start.
        // Anything that launched the app in the background — a bound accessibility service, a
        // WorkManager job — was refused at the time, so the host starts here instead of never.
        container.retryHostIfNotRunning()

        handleIncomingIntent(intent)

        setContent {
            PersonalBaseTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainPlatformScreen(container = container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        // A home-screen widget tap. Named rather than inferred from the action, because the
        // launcher starts this activity with no action at all.
        intent.getStringExtra(EXTRA_OPEN_APP)?.let { appId ->
            container.logger.i("MainActivity", "Opening app from a widget: $appId")
            container.pendingOpenApp.value = appId
        }

        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data != null) {
                    container.logger.i("MainActivity", "Received deep link intent: $data")
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val intake = container.shareTargetIntake.parseSharedIntent(intent)
                if (intake != null) {
                    container.logger.i("MainActivity", "Handled incoming share target: $intake")
                }
            }
        }
    }

    companion object {
        /** Which App a widget tap should open. */
        const val EXTRA_OPEN_APP = "com.personal.base.extra.OPEN_APP"

        /** Where inside that App to land, when the widget named one. */
        const val EXTRA_OPEN_ROUTE = "com.personal.base.extra.OPEN_ROUTE"
    }
}
