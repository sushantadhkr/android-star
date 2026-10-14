package com.personal.base.ui.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.platform.EmbeddedServer

/**
 * Documents the platform's network surface: which port it listens on, every endpoint, and how to
 * call it from a script.
 *
 * Shown on the Capabilities page because that is where the capability data is, so the reader can go
 * from "what can this do" to "how do I drive it" without leaving the page.
 */
@Composable
fun ApiReferenceCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val port = remember { EmbeddedServer.DEFAULT_PORT }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = "Capabilities API",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "listening on port $port",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AssistChip(
                    onClick = { expanded = !expanded },
                    label = { Text(if (expanded) "Hide" else "How to use") }
                )
            }

            if (expanded) {
                Text(
                    text = "Reach it from anywhere on your network:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CodeBlock("curl http://<phone-ip>:$port/api/capabilities")

                Text(
                    text = "Endpoints",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "GET  /api/capabilities        every capability, with status",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "GET  /api/capabilities/status  same payload, status alias",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "GET  /api/apps/<id>/<path>   whatever Apps that serve publish",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "Always-on host",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "The server only listens while the always-on host is running " +
                            "(Settings → Always-on Platform Host). Stop it and the phone becomes " +
                            "unreachable. A stopped App answers 503 on its own endpoints, so a " +
                            "toggle always takes effect immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Each entry reports",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "id · displayName · description · registered · switchedOn · " +
                            "usable · status · requiredPermissions",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "A capability is usable only when it is registered, switched on, and " +
                            "the master switch is on. Unusable capabilities are still listed with " +
                            "status — they are never omitted, so the full surface stays " +
                            "discoverable from a script.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Drive it from a shell",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                CodeBlock(
                    """
                    # which capabilities are usable right now
                    curl -s http://PHONE:$port/api/capabilities \
                      | grep -o '"usable":true' | wc -l

                    # everything currently blocked, and why
                    curl -s http://PHONE:$port/api/capabilities \
                      | tr ',' '\n' | grep -i permission
                    """.trimIndent()
                )

                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp)
                    )
                    Text(
                        text = "The server has no authentication. Treat it as a development " +
                                "surface and keep it off untrusted networks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}
