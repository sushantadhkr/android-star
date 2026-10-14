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
import com.personal.base.core.security.ApiTokenStore

/**
 * Shows the API token, lets it be copied for use from another machine, and rotated if it leaks.
 *
 * Rotation is instant and final: the previous token stops working the moment a new one is created.
 */
@Composable
fun ApiTokenCard(tokenStore: ApiTokenStore) {
    val context = LocalContext.current
    var token by remember { mutableStateOf(tokenStore.token()) }
    var revealed by remember { mutableStateOf(false) }
    var confirmingRotate by remember { mutableStateOf(false) }

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

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (revealed) token else "•".repeat(32),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = { revealed = !revealed },
                    label = { Text(if (revealed) "Hide" else "Show") },
                    leadingIcon = {
                        Icon(Icons.Default.Visibility, contentDescription = null)
                    }
                )
                Spacer(Modifier.width(4.dp))
                AssistChip(
                    onClick = { copyToClipboard(context, token) },
                    label = { Text("Copy") },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Use it from a shell:\n" +
                        "  curl -H \"Authorization: Bearer $token\" \\\n" +
                        "    http://<phone>:8899/api/capabilities",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

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
