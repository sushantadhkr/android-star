package com.personal.base.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.logging.LogEntry
import com.personal.base.core.logging.LogLevel
import com.personal.base.di.PlatformContainer

@Composable
fun LogsSettingsScreen(
    container: PlatformContainer,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Structured Logs") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Platform Settings") }
            )
        }

        if (selectedTab == 0) {
            LogsTab(container = container)
        } else {
            SettingsTab(container = container)
        }
    }
}

@Composable
fun LogsTab(container: PlatformContainer) {
    val entries = remember { mutableStateListOf<LogEntry>() }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        entries.clear()
        entries.addAll(container.logger.getEntries().reversed())
        container.logger.logFlow.collect { newEntry ->
            entries.add(0, newEntry)
        }
    }

    val filtered = entries.filter { entry ->
        (levelFilter == null || entry.level == levelFilter) &&
                (searchQuery.isEmpty() || entry.tag.contains(searchQuery, ignoreCase = true) || entry.message.contains(searchQuery, ignoreCase = true))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Controls: Filter chips & search
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${filtered.size} Log Entries",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(
                    onClick = {
                        val text = filtered.joinToString("\n") { "[${it.formattedTime}] [${it.level}] [${it.tag}] ${it.message}" }
                        container.platformContext.setClipboardContent(text, "Platform Logs")
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }

                OutlinedButton(
                    onClick = {
                        container.logger.clear()
                        entries.clear()
                    }
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }

        // Level chips
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("ALL") }
            )
            LogLevel.values().forEach { lvl ->
                FilterChip(
                    selected = levelFilter == lvl,
                    onClick = { levelFilter = lvl },
                    label = { Text(lvl.name) }
                )
            }
        }

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter by tag or keyword...") },
            modifier = Modifier.fillMaxWidth()
        )

        // Log list
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filtered, key = { it.id }) { entry ->
                LogItemView(entry)
            }
        }
    }
}

@Composable
fun LogItemView(entry: LogEntry) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> Color.Gray
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.WARN -> Color(0xFFE65100)
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(levelColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = entry.level.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = entry.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        entry.details?.let { d ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = d,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun SettingsTab(container: PlatformContainer) {
    val currentConfig by container.configRepository.config.collectAsState()

    var hmacSecret by remember(currentConfig) { mutableStateOf(currentConfig.authSecretHmac) }
    var syncInterval by remember(currentConfig) { mutableStateOf(currentConfig.backgroundSyncIntervalMinutes.toString()) }
    var requireSignatures by remember(currentConfig) { mutableStateOf(currentConfig.requireSignedCommands) }
    var saveSuccess by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Platform Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        OutlinedTextField(
            value = hmacSecret,
            onValueChange = { hmacSecret = it },
            label = { Text("HMAC SHA-256 Signing Secret") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = syncInterval,
            onValueChange = { syncInterval = it },
            label = { Text("Background Sync Interval (Minutes, min 15)") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Enforce Cryptographic Signatures",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Reject unauthenticated remote commands",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = requireSignatures,
                onCheckedChange = { requireSignatures = it }
            )
        }

        Button(
            onClick = {
                val intervalMins = syncInterval.toLongOrNull()?.coerceAtLeast(15L) ?: 15L
                container.configRepository.updateConfig { current ->
                    current.copy(
                        authSecretHmac = hmacSecret,
                        backgroundSyncIntervalMinutes = intervalMins,
                        requireSignedCommands = requireSignatures
                    )
                }
                container.backgroundManager.schedulePeriodicWork()
                saveSuccess = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Apply Configuration")
        }

        if (saveSuccess) {
            Text(
                text = "✓ Platform configuration updated and persisted",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
