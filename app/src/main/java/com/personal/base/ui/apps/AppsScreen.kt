package com.personal.base.ui.apps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.app.AppEntry
import com.personal.base.di.PlatformContainer

/**
 * The Apps page.
 *
 * Every registered App appears here with its safety toggle. The user alone controls the toggle;
 * switching an App off destroys its instance and clears its state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    container: PlatformContainer,
    modifier: Modifier = Modifier
) {
    val apps by container.appManager.apps.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.All) }

    val filtered = remember(apps, query, filter) {
        apps.filter { entry ->
            val matchesQuery = query.isBlank() ||
                entry.name.contains(query, ignoreCase = true) ||
                entry.registration.category.contains(query, ignoreCase = true) ||
                entry.id.contains(query, ignoreCase = true)
            val matchesFilter = when (filter) {
                AppFilter.All -> true
                AppFilter.Running -> entry.isRunning
                AppFilter.Stopped -> !entry.isRunning
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {

        // The shell's app bar already names this page.
        Spacer(Modifier.width(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.width(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label) }
                )
            }
        }
        Spacer(Modifier.width(8.dp))

        if (filtered.isEmpty()) {
            Text(
                text = "No apps match.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.id }) { entry ->
                AppCard(
                    entry = entry,
                    onToggle = { enabled -> container.appManager.setEnabled(entry.id, enabled) },
                    onOpen = { container.appManager.openApp(entry.id) }
                )
            }
        }
    }
}

private enum class AppFilter(val label: String) {
    All("All"),
    Running("Running"),
    Stopped("Stopped")
}

@Composable
private fun AppCard(
    entry: AppEntry,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isRunning, onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isRunning) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = entry.registration.icon,
                contentDescription = null,
                tint = if (entry.isRunning) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(32.dp)
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (entry.isRunning) entry.registration.description
                    else "${entry.registration.description} — stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The safety switch. The user alone controls it.
            Switch(
                checked = entry.isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}