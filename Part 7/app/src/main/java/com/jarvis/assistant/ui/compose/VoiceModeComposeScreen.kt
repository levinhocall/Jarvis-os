package com.jarvis.assistant.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.MissionOverallStatus
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.data.model.AudioTimingConfidence
import com.jarvis.assistant.data.model.VoiceConnection
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.ui.orb.JarvisOrbHostCompose
import com.jarvis.assistant.ui.orb.JarvisOrbMotionState
import com.jarvis.assistant.ui.orb.OrbPresentationLabels
import com.jarvis.assistant.ui.orb.OrbPresentationMapper
import com.jarvis.assistant.ui.orb.OrbRenderConfig
import com.jarvis.assistant.ui.theme.JarvisSpacing
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.ui.theme.LocalJarvisColors

/**
 * Full-screen distraction-free live voice UI backed by JarvisConversationService and the
 * native OpenGL ES 3.0 / AGSL voice orb engine.
 */
@Composable
fun VoiceModeComposeScreen(
    preferences: AppPreferences,
    localExplanation: String,
    showPermissionSettings: Boolean,
    transcriptExpanded: Boolean,
    onToggleTranscript: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onStartVoice: () -> Unit,
    onInterruptVoice: () -> Unit,
    onToggleMute: () -> Unit,
    onEndVoice: () -> Unit,
    onSendTextMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val presentation by JarvisConversationService.voicePresentation.collectAsStateWithLifecycle()
    val audioFreq by JarvisConversationService.audioFrequency.collectAsStateWithLifecycle()
    val conversationState by JarvisConversationService.conversationState.collectAsStateWithLifecycle()
    val isStandby by JarvisConversationService.isStandby.collectAsStateWithLifecycle()
    val liveUserTranscript by JarvisConversationService.liveUserTranscript.collectAsStateWithLifecycle()
    val liveAssistantResponse by JarvisConversationService.liveAssistantResponse.collectAsStateWithLifecycle()
    val liveError by JarvisConversationService.liveError.collectAsStateWithLifecycle()
    val activeMission by MissionEngine.activeMission.collectAsStateWithLifecycle()

    var mutedState by remember { mutableStateOf(preferences.isMicMuted) }
    var showKeyboardDialog by remember { mutableStateOf(false) }
    var typedInput by remember { mutableStateOf("") }

    val renderConfig = remember(preferences.orbPreset, preferences.orbColorTheme, preferences.orbEyeOptions) {
        OrbRenderConfig.fromPreferences(
            orbPresetRaw = preferences.orbPreset,
            colorThemeRaw = preferences.orbColorTheme,
            eyeOptionsJson = preferences.orbEyeOptions
        )
    }

    val motionState = remember(presentation, conversationState, isStandby, mutedState) {
        JarvisOrbMotionState.resolve(
            isSessionOn = presentation.active,
            isStandby = isStandby,
            isMicMuted = mutedState || presentation.muted,
            conversationState = conversationState,
            snapshot = presentation
        )
    }

    val statusLabel = remember(presentation) {
        OrbPresentationLabels.status(context, presentation)
    }
    val mappedReason = remember(presentation, localExplanation, liveError) {
        liveError?.message
            ?: OrbPresentationMapper.map(presentation, android.os.SystemClock.elapsedRealtime()).reason
            ?: if (presentation.playing && presentation.timing == AudioTimingConfidence.STATE_ONLY) {
                "Speaking"
            } else {
                localExplanation
            }
    }

    JarvisTheme(themeKey = "dark", customHex = preferences.customThemeColor) {
        val colors = LocalJarvisColors.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .systemBarsPadding()
                .padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = onBack,
                    border = BorderStroke(1.dp, colors.cardBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Back", color = colors.textPrimary)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "JARVIS LIVE",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    Text(
                        text = renderConfig.preset.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.accent
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xxs)) {
                    TextButton(onClick = onOpenAppearance) {
                        Text("Style", color = colors.accent)
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("API", color = colors.textSecondary)
                    }
                }
            }

            // Center Scrollable Orb + Transcript Area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                JarvisOrbHostCompose(
                    config = renderConfig,
                    motionState = motionState,
                    rms = maxOf(audioFreq.amplitude, presentation.output.fresh(System.currentTimeMillis(), 0)),
                    bass = audioFreq.bass,
                    mid = audioFreq.mid,
                    treble = audioFreq.high,
                    accessibilityLabel = statusLabel,
                    onTap = {
                        if (presentation.active) onInterruptVoice() else onStartVoice()
                    },
                    onLongPress = onOpenAppearance,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .heightIn(min = 220.dp, max = 330.dp)
                        .aspectRatio(1f)
                )

                Spacer(modifier = Modifier.height(JarvisSpacing.sm))

                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary
                )

                if (activeMission?.status == MissionOverallStatus.EXECUTING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Autonomous Mission Executing...",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.statusAmber
                    )
                }

                if (mappedReason.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = mappedReason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }

                if (showPermissionSettings) {
                    Spacer(modifier = Modifier.height(JarvisSpacing.xs))
                    OutlinedButton(onClick = onOpenPermissionSettings) {
                        Text("Open App Permissions", color = colors.accent)
                    }
                }

                Spacer(modifier = Modifier.height(JarvisSpacing.sm))

                Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
                    if (!presentation.active) {
                        Button(
                            onClick = onStartVoice,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Start Voice", color = colors.background)
                        }
                    }
                    OutlinedButton(
                        onClick = onToggleTranscript,
                        border = BorderStroke(1.dp, colors.cardBorder)
                    ) {
                        Text(
                            text = if (transcriptExpanded) "Hide Transcript" else "Show Transcript",
                            color = colors.textSecondary
                        )
                    }
                }

                if (transcriptExpanded) {
                    Spacer(modifier = Modifier.height(JarvisSpacing.sm))
                    Surface(
                        color = colors.surfaceCard,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, colors.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(JarvisSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
                        ) {
                            Text("YOU", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                            Text(
                                text = liveUserTranscript.ifBlank { "Waiting for speech..." },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary
                            )
                            Text("JARVIS", style = MaterialTheme.typography.labelSmall, color = colors.statusPink)
                            Text(
                                text = liveAssistantResponse.ifBlank { "—" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }

            // Bottom Control Dock
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
                ) {
                    OutlinedButton(
                        onClick = {
                            onToggleMute()
                            mutedState = preferences.isMicMuted
                        },
                        border = BorderStroke(1.dp, colors.cardBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (mutedState) "Unmute" else "Mute", color = colors.textPrimary)
                    }

                    OutlinedButton(
                        onClick = onInterruptVoice,
                        enabled = presentation.active,
                        border = BorderStroke(1.dp, colors.cardBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Interrupt", color = colors.textPrimary)
                    }

                    OutlinedButton(
                        onClick = onEndVoice,
                        enabled = presentation.active,
                        border = BorderStroke(1.dp, colors.statusRed.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("End", color = colors.statusRed)
                    }
                }

                Button(
                    onClick = { showKeyboardDialog = true },
                    enabled = presentation.active && presentation.connection == VoiceConnection.LIVE,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.surfaceCard,
                        contentColor = colors.textPrimary
                    ),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Type message to live session")
                }
            }
        }

        if (showKeyboardDialog) {
            AlertDialog(
                onDismissRequest = { showKeyboardDialog = false },
                title = { Text("Send message") },
                text = {
                    OutlinedTextField(
                        value = typedInput,
                        onValueChange = { typedInput = it },
                        placeholder = { Text("Ask Jarvis anything...") },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cleaned = typedInput.trim()
                        if (cleaned.isNotEmpty()) {
                            onSendTextMessage(cleaned)
                            typedInput = ""
                        }
                        showKeyboardDialog = false
                    }) {
                        Text("Send")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showKeyboardDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
