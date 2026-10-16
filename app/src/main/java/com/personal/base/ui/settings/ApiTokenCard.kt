package com.personal.base.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.ContextWrapper
import android.widget.Toast
import com.personal.base.core.security.ApiTokenStore
import com.personal.base.platform.capabilities.BiometricAuthCapabilityProvider

/**
 * Shows the API token, lets it be copied for use from another machine, and rotated if it leaks.
 *
 * Rotation is instant and final: the previous token stops working the moment a new one is created.
 */
@Composable
fun ApiTokenCard(
    tokenStore: ApiTokenStore,
    biometrics: BiometricAuthCapabilityProvider
) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(tokenStore.token()) }
    var revealed by remember { mutableStateOf(false) }
    var confirmingRotate by remember { mutableStateOf(false) }

    // Re-mask on the way out. Leaving a secret on screen because someone navigated away is how it
    // ends up in a screenshot, a screen recording or over someone's shoulder.
    DisposableEffect(Unit) {
        onDispose { revealed = false }
    }

    // The token is the one thing here that grants full control of this phone to whoever holds it,
    // so neither reading it nor putting it on the clipboard happens without the owner's fingerprint
    // or screen lock first. It is masked by default, and it stays masked again as soon as the
    // settings screen is left.
    fun requireIdentity(then: () -> Unit) {
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, "Could not open the security prompt.", Toast.LENGTH_SHORT).show()
            return
        }
        biometrics.confirmIdentity(
            activity = activity,
            title = "Show API token",
            subtitle = "Verify your fingerprint or device lock to read it",
            onSuccess = {
                revealed = true
                then()
            },
            onError = { reason ->
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "API access token",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Required on every request to the platform's HTTP surface",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (revealed) token else "•".repeat(32),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = {
                        if (revealed) {
                            // Hiding needs no proof: it is the safe direction.
                            revealed = false
                        } else {
                            requireIdentity { }
                        }
                    },
                    label = { Text(if (revealed) "Hide" else "Show") },
                    leadingIcon = {
                        Icon(Icons.Default.Visibility, contentDescription = null)
                    }
                )
                Spacer(Modifier.width(4.dp))
                AssistChip(
                    onClick = { requireIdentity { copyToClipboard(context, token) } },
                    label = { Text("Copy") },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            if (confirmingRotate) {
                Text(
                    text = "Rotate now? The current token stops working immediately and any " +
                            "script or device using it will start getting 401.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        token = tokenStore.rotate()
                        revealed = false
                        confirmingRotate = false
                    }) {
                        Text("Yes, rotate")
                    }
                    OutlinedButton(onClick = { confirmingRotate = false }) {
                        Text("Cancel")
                    }
                }
            } else {
                OutlinedButton(onClick = { confirmingRotate = true }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Rotate token")
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, token: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Android Star API token", token))
}

/**
 * Unwraps the Activity from a Compose context, which is what BiometricPrompt needs.
 *
 * Returns null rather than guessing: a prompt constructed against the wrong context fails with an
 * error the user cannot act on.
 */
private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
