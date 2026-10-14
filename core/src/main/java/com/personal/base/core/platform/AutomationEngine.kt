package com.personal.base.core.platform

import com.personal.base.core.logging.PlatformLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class AutomationRule(
    val id: String,
    val name: String,
    val triggerEventType: String,
    val condition: (PlatformEvent) -> Boolean,
    val action: suspend (PlatformEvent) -> Unit,
    val isEnabled: Boolean = true
)

/**
 * Trigger-Condition-Action automation engine responding to system and capability events.
 */
class AutomationEngine(
    private val eventBus: EventBus,
    private val logger: PlatformLogger
) {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val rules = ConcurrentHashMap<String, AutomationRule>()

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                rules.values.filter { it.isEnabled && it.triggerEventType == event.type }.forEach { rule ->
                    try {
                        if (rule.condition(event)) {
                            logger.i("AutomationEngine", "Executing automation rule: ${rule.name}")
                            rule.action(event)
                        }
                    } catch (e: Exception) {
                        logger.e("AutomationEngine", "Error executing rule '${rule.name}': ${e.message}", e.message)
                    }
                }
            }
        }
        logger.i("AutomationEngine", "Automation engine initialized.")
    }

    fun addRule(rule: AutomationRule) {
        rules[rule.id] = rule
        logger.i("AutomationEngine", "Added automation rule: ${rule.name}")
    }

    fun removeRule(ruleId: String) {
        rules.remove(ruleId)
    }
}
