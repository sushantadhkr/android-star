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
fun ApiReferenceCard(tokenStore: com.personal.base.core.security.ApiTokenStore) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val port = remember { EmbeddedServer.DEFAULT_PORT }
    val token = remember { tokenStore.token() }

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
                    text = "Every request needs the token from Settings → API access token:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CodeBlock(
                    """
                    # the full self-describing reference
                    curl -H "Authorization: Bearer $token" http://PHONE:$port/api/capabilities/docs \
                      | jq '.capabilities[] | {id, actions: [.actions[].name]}'
                    """.trimIndent()
                )

                Text(
                    text = "Endpoints",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "GET  /api/capabilities/docs     full docs for every action",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "GET  /api/capabilities         every capability, status + actions",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "POST /api/capabilities/invoke  run a capability action",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "POST /api/capabilities/toggle  switch one, or all, on and off",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "GET  /api/apps/<id>/<path>    whatever Apps that serve publish",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "Drive a capability",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                CodeBlock(
                    """
                    # post a notification
                    curl -H "Authorization: Bearer $token" -X POST http://PHONE:$port/api/capabilities/invoke \\
                      -d '{"capability":"NOTIFICATIONS","action":"post",
                           "title":"Hello","message":"from curl"}'

                    # tappable: opens the link instead of the app
                    curl -H "Authorization: Bearer $token" -X POST http://PHONE:$port/api/capabilities/invoke \\
                      -d '{"capability":"NOTIFICATIONS","action":"post","title":"Build passed",
                           "message":"Tap to view","link":"https://ci.example.com/build/482"}'

                    # vibrate
                    curl -H "Authorization: Bearer $token" -X POST http://PHONE:$port/api/capabilities/invoke \\
                      -d '{"capability":"DEVICE_ACTIONS","action":"haptic","durationMs":200}'

                    # read sensors
                    curl -H "Authorization: Bearer $token" -X POST http://PHONE:$port/api/capabilities/invoke \\
                      -d '{"capability":"SENSORS","action":"get"}'
                    """.trimIndent()
                )

                Text(
                    text = "Switch a capability off",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                CodeBlock(
                    """
                    # off: the API refuses to touch it, 403
                    curl -H "Authorization: Bearer $token" -X POST http://PHONE:$port/api/capabilities/toggle \\
                      -d '{"capability":"CAMERA","enabled":false}'

                    # master switch, everything at once
                    curl -H "Authorization: Bearer $token" -X POST http://PHONE:$port/api/capabilities/toggle \\
                      -d '{"enabled":false}'
                    """.trimIndent()
                )

                Text(
                    text = "A capability you switch off is never invoked — the gate is checked " +
                            "before the action runs, so the API returns 403 rather than quietly " +
                            "doing nothing. Disabled capabilities stay listed in the catalogue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Always-on host",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "The server only listens while the always-on host is running " +
                            "(Settings → Always-on Platform Host). Stop it and the phone becomes " +
                            "unreachable. A stopped App answers 503 on its own endpoints.",
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
                        text = "The token is the only thing protecting this surface. If it leaks, " +
                                "rotate it in Settings — the old one stops working immediately. " +
                                "Keep it off untrusted networks.",
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
