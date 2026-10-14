package com.personal.base.apps.hello

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.base.core.app.AppContribution
import com.personal.base.core.app.AppScope
import com.personal.base.core.app.PlatformApp
import com.personal.base.core.app.PlatformAppDefinition
import com.personal.base.core.command.CommandResult
import com.personal.base.core.command.RemoteCommand
import com.personal.base.core.plugin.PlatformContext

/**
 * A second App, written without touching a single shell file.
 *
 * Its only presence in the platform is this file: the registry is generated from the annotation,
 * the Apps page picks it up automatically, and its card renders because it declares one.
 *
 * This is the proof that adding an App is additive.
 */
@PlatformAppDefinition(id = "hello", name = "Hello", order = 20)
class HelloApp : PlatformApp {

    override val id = "hello"
    override val name = "Hello"
    override val icon = Icons.Default.WavingHand
    override val description = "A minimal App demonstrating the contract."
    override val version = "1.0"
    override val category = "Tools"

    /** A headless state counter — wiped when the App is switched off. */
    private var greetings = 0

    override val contribution = AppContribution(
        dashboardCard = { scope -> GreetingCard(scope) }
    )

    override fun onInitialize(context: PlatformContext) {
        context.logger.i("HelloApp", "Hello App started")
    }

    override suspend fun handleCommand(command: RemoteCommand): CommandResult {
        greetings++
        return CommandResult.success(command.id, "Hello! ($greetings)")
    }

    @Composable
    override fun AppContent(scope: AppScope, onBack: () -> Unit) {
        Text("Hello", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GreetingCard(scope: AppScope) {
    Text(
        text = if (scope.isEnabled) "Hello from an App" else "Disabled",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(0.dp)
    )
}