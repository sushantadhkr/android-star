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
import com.personal.base.theme.PersonalBaseTheme
import com.personal.base.ui.MainPlatformScreen

class MainActivity : ComponentActivity() {

    private val container by lazy {
        (application as PersonalBaseApplication).container
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}
