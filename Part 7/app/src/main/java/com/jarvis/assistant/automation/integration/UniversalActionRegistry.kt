package com.jarvis.assistant.automation.integration

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.assistant.automation.accessibility.AccessibilityHealthModel
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import java.util.concurrent.ConcurrentHashMap

object UniversalActionRegistry {

    private val contracts = ConcurrentHashMap<String, UniversalToolContract>()

    init {
        registerBuiltIns()
    }

    private fun registerBuiltIns() {
        register(
            UniversalToolContract(
                identifier = "launch_app",
                description = "Launches an installed Android application by package name or alias",
                parameterSchemas = listOf(
                    ActionParameterSchema("package_name", "String", "Android package name or common app name")
                ),
                riskLevel = ActionRiskLevel.LOW,
                isIdempotent = true,
                verificationStrategy = VerificationStrategy.RETURN_VALUE,
                sideEffectsDescription = "Brings target application to foreground"
            )
        )

        register(
            UniversalToolContract(
                identifier = "call_contact",
                description = "Initiates a cellular phone call to a contact",
                parameterSchemas = listOf(
                    ActionParameterSchema("contact_name", "String", "Name of contact to call"),
                    ActionParameterSchema("contact_index", "Int", "Optional index disambiguation", required = false)
                ),
                requiredPermissions = listOf(
                    android.Manifest.permission.READ_CONTACTS,
                    android.Manifest.permission.CALL_PHONE
                ),
                riskLevel = ActionRiskLevel.MEDIUM,
                isIdempotent = false,
                verificationStrategy = VerificationStrategy.RETURN_VALUE,
                sideEffectsDescription = "Dials phone number using device dialer"
            )
        )

        val whatsappContract = UniversalToolContract(
            identifier = "whatsapp_send",
            description = "Sends a message to a WhatsApp contact",
            parameterSchemas = listOf(
                ActionParameterSchema("contact", "String", "Recipient name or phone number"),
                ActionParameterSchema("message", "String", "Message text content")
            ),
            requiredPermissions = listOf(
                android.Manifest.permission.READ_CONTACTS
            ),
            requiresAccessibility = true,
            riskLevel = ActionRiskLevel.HIGH_CONSEQUENTIAL,
            isIdempotent = false,
            verificationStrategy = VerificationStrategy.CLOSED_LOOP_ACCESSIBILITY,
            sideEffectsDescription = "Dispatches external message across messaging network"
        )
        register(whatsappContract)
        register(whatsappContract.copy(identifier = "send_whatsapp"))

        register(
            UniversalToolContract(
                identifier = "device_control",
                description = "Controls hardware toggles like flashlight, volume, brightness",
                parameterSchemas = listOf(
                    ActionParameterSchema("command", "String", "Hardware instruction (e.g. 'turn on flashlight')")
                ),
                riskLevel = ActionRiskLevel.LOW,
                isIdempotent = true,
                verificationStrategy = VerificationStrategy.SYSTEM_STATE,
                sideEffectsDescription = "Modifies device hardware settings"
            )
        )

        register(
            UniversalToolContract(
                identifier = "search_web",
                description = "Performs a web search via the system browser",
                parameterSchemas = listOf(
                    ActionParameterSchema("query", "String", "Web search query")
                ),
                riskLevel = ActionRiskLevel.LOW,
                isIdempotent = true,
                verificationStrategy = VerificationStrategy.RETURN_VALUE
            )
        )

        register(
            UniversalToolContract(
                identifier = "youtube_play",
                description = "Searches and streams media on YouTube",
                parameterSchemas = listOf(
                    ActionParameterSchema("query", "String", "Video title or artist query")
                ),
                riskLevel = ActionRiskLevel.LOW,
                isIdempotent = true,
                verificationStrategy = VerificationStrategy.RETURN_VALUE
            )
        )

        register(
            UniversalToolContract(
                identifier = "scroll_screen",
                description = "Scrolls active window up or down",
                parameterSchemas = listOf(
                    ActionParameterSchema("direction", "String", "Scroll direction: 'up' or 'down'"),
                    ActionParameterSchema("distance", "Int", "Relative scroll units", required = false)
                ),
                requiresAccessibility = true,
                riskLevel = ActionRiskLevel.LOW,
                isIdempotent = false,
                verificationStrategy = VerificationStrategy.CLOSED_LOOP_ACCESSIBILITY
            )
        )

        register(
            UniversalToolContract(
                identifier = "navigate_home",
                description = "Navigates to home screen using native Android Intent",
                parameterSchemas = emptyList(),
                riskLevel = ActionRiskLevel.LOW,
                isIdempotent = true,
                verificationStrategy = VerificationStrategy.RETURN_VALUE
            )
        )

        register(
            UniversalToolContract(
                identifier = "navigate_back",
                description = "Performs global system back gesture",
                parameterSchemas = emptyList(),
                requiresAccessibility = true,
                riskLevel = ActionRiskLevel.LOW,
                isIdempotent = false,
                verificationStrategy = VerificationStrategy.CLOSED_LOOP_ACCESSIBILITY
            )
        )
    }

    fun register(contract: UniversalToolContract) {
        contracts[contract.identifier] = contract
    }

    fun getContract(identifier: String): UniversalToolContract? = contracts[identifier]

    fun getAllContracts(): List<UniversalToolContract> = contracts.values.toList()

    /**
     * Validates arguments, permissions, accessibility readiness, and risk confirmation before execution.
     */
    fun validateInvocation(
        identifier: String,
        parameters: Map<String, Any?>,
        context: Context? = null,
        isConfirmedByUser: Boolean = false
    ): UniversalExecutionResult? {
        val contract = contracts[identifier]
            ?: return UniversalExecutionResult.Failure(
                toolIdentifier = identifier,
                error = "Tool '$identifier' is not registered in Universal Action Registry.",
                isRecoverable = false
            )

        // 1. Validate required parameter schemas
        val missingParams = mutableListOf<String>()
        for (schema in contract.parameterSchemas) {
            if (schema.required) {
                val value = parameters[schema.name]
                if (value == null || (value is String && value.isBlank())) {
                    missingParams.add(schema.name)
                }
            }
        }
        if (missingParams.isNotEmpty()) {
            return UniversalExecutionResult.Failure(
                toolIdentifier = identifier,
                error = "Missing required parameters: ${missingParams.joinToString()}",
                isRecoverable = true
            )
        }

        // 2. Validate High Consequential Confirmation
        if (contract.riskLevel == ActionRiskLevel.HIGH_CONSEQUENTIAL && !isConfirmedByUser) {
            return UniversalExecutionResult.ConfirmationRequired(
                toolIdentifier = identifier,
                prompt = "Please confirm: Do you want me to execute '${contract.identifier}' with side effects: '${contract.sideEffectsDescription}'?",
                actionSummary = "${contract.identifier}(${parameters})"
            )
        }

        // 3. Validate runtime permissions
        if (context != null && contract.requiredPermissions.isNotEmpty()) {
            val missingPerms = contract.requiredPermissions.filter { perm ->
                ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPerms.isNotEmpty()) {
                val guidance = "Permission required: ${missingPerms.joinToString { it.substringAfterLast('.') }}."
                val settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                return UniversalExecutionResult.PermissionDenied(
                    toolIdentifier = identifier,
                    missingPermissions = missingPerms,
                    settingsAction = settingsAction,
                    guidance = guidance
                )
            }
        }

        // 4. Validate Accessibility readiness
        if (contract.requiresAccessibility) {
            val isOperational = JarvisAccessibilityService.isRunning() || AccessibilityHealthModel.isOperational()
            if (!isOperational) {
                val guidance = AccessibilityHealthModel.getRecoveryGuidance()
                    ?: "Accessibility Service is not running. Please enable JARVIS OS in Accessibility Settings."
                return UniversalExecutionResult.ServiceUnavailable(
                    toolIdentifier = identifier,
                    reason = "Accessibility service is inactive or not operational.",
                    guidance = guidance
                )
            }
        }

        // Everything valid
        return null
    }

    /**
     * Discovers installed applications dynamically on device.
     */
    fun discoverInstalledApps(context: Context): List<String> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolveInfos.mapNotNull { it.activityInfo?.packageName }.distinct()
    }
}
