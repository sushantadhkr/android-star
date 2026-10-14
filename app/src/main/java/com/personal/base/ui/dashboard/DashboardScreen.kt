package com.personal.base.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.app.AppScopeImpl
import com.personal.base.core.app.DashboardItem
import com.personal.base.di.PlatformContainer
import kotlinx.coroutines.launch

/**
 * The Dashboard.
 *
 * It is empty by default and stays empty until Apps contribute a Card or an Action. The user
 * controls what is visible and in what order; a stopped App keeps its place and simply reads as
 * disabled.
 */
@Composable
fun DashboardScreen(
    container: PlatformContainer,
    modifier: Modifier = Modifier
) {
    val apps by container.appManager.apps.collectAsState()
    val composer = container.dashboardComposer
    val layoutStore = container.dashboardLayoutStore

    var arranging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val items = remember(apps, arranging) { composer.compose(apps) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {

        // The shell's app bar already names this page. This row carries only the arrange action.
        if (items.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (arranging) "Done" else "Arrange",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { arranging = !arranging }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (items.isEmpty()) {
            EmptyDashboard()
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                DashboardRow(
                    item = item,
                    container = container,
                    arranging = arranging,
                    onHide = { layoutStore.setHidden(item.key, true) },
                    onMoveUp = {
                        if (index > 0) {
                            val order = items.map { it.key }.toMutableList()
                            val moved = order.removeAt(index)
                            order.add(index - 1, moved)
                            layoutStore.setVisibleItems(order)
                        }
                    },
                    onMoveDown = {
                        if (index < items.lastIndex) {
                            val order = items.map { it.key }.toMutableList()
                            val moved = order.removeAt(index)
                            order.add(index + 1, moved)
                            layoutStore.setVisibleItems(order)
                        }
                    },
                    onInvoke = {
                        (item as? DashboardItem.Action)?.let { action ->
                            scope.launch { action.onInvoke() }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DashboardRow(
    item: DashboardItem,
    container: PlatformContainer,
    arranging: Boolean,
    onHide: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onInvoke: () -> Unit
) {
    val enabled = item.isEnabled
    val colors = if (enabled) {
        CardDefaults.cardColors()
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = colors
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            if (arranging) {
                Column {
                    IconButton(onClick = onMoveUp) {
                        Icon(Icons.Default.DragHandle, contentDescription = "Move up")
                    }
                    IconButton(onClick = onHide) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = "Hide")
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            when (item) {
                is DashboardItem.Card -> {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.appName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        val content = item.content
                        if (enabled && content != null) {
                            content(AppScopeImpl(container.appManager, item.appId, container.platformContext))
                        } else {
                            StoppedNotice()
                        }
                    }
                }

                is DashboardItem.Action -> {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (!enabled) {
                            Text(
                                text = "App is switched off",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onInvoke,
                        enabled = enabled
                    ) {
                        Text("Run")
                    }
                }
            }
        }
    }
}

@Composable
private fun StoppedNotice() {
    Text(
        text = "Disabled — switch this app on from the Apps page",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun EmptyDashboard() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Visibility,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Nothing on the dashboard yet",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Apps you add can place a card or an action here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}