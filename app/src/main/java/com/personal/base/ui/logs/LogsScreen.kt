package com.personal.base.ui.logs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogsScreen(
    container: PlatformContainer,
    modifier: Modifier = Modifier
) {
    val entries = remember { mutableStateListOf<LogEntry>() }
    var selectedAppTag by remember { mutableStateOf<String?>(null) }
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isLiveFeedPaused by remember { mutableStateOf(false) }
    var detailedLog by remember { mutableStateOf<LogEntry?>(null) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val registeredApps by container.appManager.apps.collectAsState()

    // Collect log entries
    LaunchedEffect(isLiveFeedPaused) {
        if (!isLiveFeedPaused && entries.isEmpty()) {
            entries.clear()
            entries.addAll(container.logger.getEntries().reversed())
        }
        container.logger.logFlow.collect { newEntry ->
            if (!isLiveFeedPaused) {
                entries.add(0, newEntry)
                // Cap in-memory buffer at 1000 items to prevent memory bloat
                if (entries.size > 1000) {
                    entries.removeAt(entries.size - 1)
                }
            }
        }
    }

    // App categories
    val appCategoryMap = remember(registeredApps) {
        val map = mutableMapOf<String, (LogEntry) -> Boolean>()
        map["ALL"] = { true }
        registeredApps.forEach { entry ->
            map[entry.name] = { log: LogEntry ->
                log.tag.contains(entry.id, ignoreCase = true) ||
                log.tag.contains(entry.name, ignoreCase = true) ||
                (entry.name == "System Stats" && (log.tag.contains("Connectivity") || log.tag.contains("DeviceAction") || log.tag.contains("Bluetooth") || log.tag.contains("Sensors"))) ||
                (entry.name == "File Server" && log.tag.contains("FileServer", ignoreCase = true)) ||
                (entry.name == "Star Chat" && log.tag.contains("NearbyChat", ignoreCase = true))
            }
        }
        map["Platform Core"] = { entry ->
            entry.tag.contains("PlatformContainer") || entry.tag.contains("Background") || entry.tag.contains("Foreground") || entry.tag.contains("HttpChannel") || entry.tag.contains("DeviceSigner")
        }
        map
    }

    // Count statistics
    val totalCount = entries.size
    val errorCount = entries.count { it.level == LogLevel.ERROR }
    val warnCount = entries.count { it.level == LogLevel.WARN }
    val infoCount = entries.count { it.level == LogLevel.INFO }

    // Filtered list
    val filtered = entries.filter { entry ->
        val matchesCategory = if (selectedAppTag == null || selectedAppTag == "ALL") {
            true
        } else {
            appCategoryMap[selectedAppTag]?.invoke(entry) ?: entry.tag.contains(selectedAppTag!!, ignoreCase = true)
        }

        val matchesLevel = levelFilter == null || entry.level == levelFilter
        val matchesSearch = searchQuery.isBlank() ||
                entry.tag.contains(searchQuery, ignoreCase = true) ||
                entry.message.contains(searchQuery, ignoreCase = true) ||
                (entry.details?.contains(searchQuery, ignoreCase = true) == true)

        matchesCategory && matchesLevel && matchesSearch
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("System & App Logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "${filtered.size} visible of $totalCount buffered" + if (isLiveFeedPaused) " • (PAUSED)" else " • (LIVE)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLiveFeedPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Pause / Resume Stream
                IconButton(onClick = { isLiveFeedPaused = !isLiveFeedPaused }) {
                    Icon(
                        if (isLiveFeedPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isLiveFeedPaused) "Resume" else "Pause",
                        tint = if (isLiveFeedPaused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Share logs
                IconButton(onClick = {
                    val exportText = filtered.joinToString("\n") { "[${it.formattedTime}] [${it.level}] [${it.tag}] ${it.message}" }
                    container.platformContext.shareText(exportText, "Export Android Star Logs")
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share Logs")
                }

                // Copy logs
                IconButton(onClick = {
                    val exportText = filtered.joinToString("\n") { "[${it.formattedTime}] [${it.level}] [${it.tag}] ${it.message}" }
                    container.platformContext.setClipboardContent(exportText, "Platform Logs")
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                }

                // Clear logs
                IconButton(onClick = { showClearConfirmDialog = true }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Metrics KPI Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricBadgeCard(
                label = "Total",
                count = totalCount,
                color = MaterialTheme.colorScheme.primary,
                isSelected = levelFilter == null,
                onClick = { levelFilter = null },
                modifier = Modifier.weight(1f)
            )
            MetricBadgeCard(
                label = "Errors",
                count = errorCount,
                color = MaterialTheme.colorScheme.error,
                isSelected = levelFilter == LogLevel.ERROR,
                onClick = { levelFilter = if (levelFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                modifier = Modifier.weight(1f)
            )
            MetricBadgeCard(
                label = "Warnings",
                count = warnCount,
                color = Color(0xFFE65100),
                isSelected = levelFilter == LogLevel.WARN,
                onClick = { levelFilter = if (levelFilter == LogLevel.WARN) null else LogLevel.WARN },
                modifier = Modifier.weight(1f)
            )
            MetricBadgeCard(
                label = "Info",
                count = infoCount,
                color = Color(0xFF0284C7),
                isSelected = levelFilter == LogLevel.INFO,
                onClick = { levelFilter = if (levelFilter == LogLevel.INFO) null else LogLevel.INFO },
                modifier = Modifier.weight(1f)
            )
        }

        // App & Component Filter row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            appCategoryMap.keys.forEach { appKey ->
                val isSelected = (appKey == "ALL" && (selectedAppTag == null || selectedAppTag == "ALL")) || selectedAppTag == appKey
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        selectedAppTag = if (appKey == "ALL") null else appKey
                    },
                    label = { Text(appKey) }
                )
            }
        }

        // Search text field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter logs by keyword, tag, or message...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // Log List
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("No log entries match the active filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    LogItemRow(entry = entry, onClick = { detailedLog = entry })
                }
            }
        }
    }

    // Clear confirmation dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Clear All Logs?") },
            text = { Text("This will permanently remove all $totalCount buffered logs from device memory.") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        container.logger.clear()
                        entries.clear()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dedicated Log Detail Modal
    detailedLog?.let { entry ->
        LogDetailDialog(
            entry = entry,
            onDismiss = { detailedLog = null },
            onCopy = {
                val full = "[${entry.formattedTime}] [${entry.level}] [${entry.tag}]\n${entry.message}\n${entry.details ?: ""}"
                container.platformContext.setClipboardContent(full, "Log Entry")
            }
        )
    }
}

@Composable
private fun MetricBadgeCard(
    label: String,
    count: Int,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, color) else null,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$count", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
fun LogItemRow(
    entry: LogEntry,
    onClick: () -> Unit
) {
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> Color.Gray
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.WARN -> Color(0xFFE65100)
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(levelColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = entry.level.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = levelColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
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
            fontFamily = FontFamily.Monospace,
            maxLines = 2
        )
    }
}

@Composable
fun LogDetailDialog(
    entry: LogEntry,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log Entry Detail", fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Badge { Text(entry.level.name) }
                    Text(entry.formattedTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider()
                Text("Source Tag / Module:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(entry.tag, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)

                Spacer(modifier = Modifier.height(4.dp))
                Text("Message:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                val details = entry.details
                if (details != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Details / Stack Trace:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copy Entry")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
