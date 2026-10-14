package com.personal.base.ui.command

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.command.UserRole
import com.personal.base.di.PlatformContainer
import kotlinx.coroutines.launch

@Composable
fun CommandScreen(
    container: PlatformContainer,
    modifier: Modifier = Modifier
) {
    val config by container.configRepository.config.collectAsState()
    val scope = rememberCoroutineScope()

    var targetPlugin by remember { mutableStateOf("system-diagnostics") }
    var actionName by remember { mutableStateOf("run") }
    var selectedRole by remember { mutableStateOf(UserRole.ADMIN) }
    var signatureMode by remember { mutableStateOf(SignatureMode.VALID) }

    var lastResult by remember { mutableStateOf<CommandResult?>(null) }
    var isExecuting by remember { mutableStateOf(false) }

    val plugins = listOf("system-diagnostics", "echo", "quick-actions", "security-audit")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Remote Command Console",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Test command authorization, anti-replay TTL verification, cryptographic HMAC signatures, and plugin dispatch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Command Builder Card
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Command Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Target Plugin Selector
                Text("Target Plugin:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    plugins.forEach { pluginId ->
                        FilterChip(
                            selected = targetPlugin == pluginId,
                            onClick = {
                                targetPlugin = pluginId
                                actionName = when (pluginId) {
                                    "system-diagnostics" -> "run"
                                    "echo" -> "ping"
                                    "quick-actions" -> "toggle_torch"
                                    "security-audit" -> "audit"
                                    else -> "ping"
                                }
                            },
                            label = { Text(pluginId.split("-").first().capitalize()) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = actionName,
                    onValueChange = { actionName = it },
                    label = { Text("Action Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Role Selector
                Text("Caller Authorization Role:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    UserRole.values().forEach { role ->
                        FilterChip(
                            selected = selectedRole == role,
                            onClick = { selectedRole = role },
                            label = { Text(role.name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Signature Test Mode
                Text("HMAC Security Simulation:", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SignatureMode.values().forEach { mode ->
                        FilterChip(
                            selected = signatureMode == mode,
                            onClick = { signatureMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            isExecuting = true
                            val timestamp = if (signatureMode == SignatureMode.EXPIRED) {
                                System.currentTimeMillis() - 400_000L // Exceeds 300s TTL
                            } else {
                                System.currentTimeMillis()
                            }

                            val baseCommand = RemoteCommand(
                                id = "cmd-${System.currentTimeMillis()}",
                                targetPluginId = targetPlugin,
                                action = actionName,
                                senderId = "console-client",
                                role = selectedRole,
                                timestamp = timestamp,
                                ttlSeconds = 300L
                            )

                            val signature = when (signatureMode) {
                                SignatureMode.VALID -> container.commandValidator.computeHmac(baseCommand, config.authSecretHmac)
                                SignatureMode.INVALID -> "invalid_tampered_hmac_signature"
                                SignatureMode.EXPIRED -> container.commandValidator.computeHmac(baseCommand, config.authSecretHmac)
                            }

                            val command = baseCommand.copy(signature = signature)
                            val result = container.pluginManager.dispatchCommand(command)
                            lastResult = result
                            isExecuting = false
                        }
                    },
                    enabled = !isExecuting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isExecuting) "Dispatching..." else "Execute Command")
                }
            }
        }

        // Execution Result Card
        lastResult?.let { res ->
            OutlinedCard(
                colors = CardDefaults.outlinedCardColors(
                    containerColor = if (res.isSuccess) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (res.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (res.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (res.isSuccess) "Command Succeeded" else "Command Rejected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (res.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }

                        Badge {
                            Text("${res.executionTimeMs} ms")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = res.message,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (res.errorCode != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Error Code: ${res.errorCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (res.output.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Output Payload:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        res.output.forEach { (k, v) ->
                            Text(
                                text = "  $k: $v",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

enum class SignatureMode(val label: String) {
    VALID("Valid HMAC"),
    INVALID("Tampered Signature"),
    EXPIRED("Expired TTL")
}

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
