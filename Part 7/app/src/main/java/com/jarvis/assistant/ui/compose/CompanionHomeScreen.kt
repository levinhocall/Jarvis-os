package com.jarvis.assistant.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.automation.agent.AgentTaskEngine
import com.jarvis.assistant.automation.agent.TaskStepStatus
import com.jarvis.assistant.automation.mission.MissionEngine
import com.jarvis.assistant.automation.mission.MissionOverallStatus
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.background.JarvisLiveError
import com.jarvis.assistant.core.runtime.JarvisEvent
import com.jarvis.assistant.core.runtime.JarvisEventBus
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.ui.home.MainViewModel
import com.jarvis.assistant.ui.orb.JarvisOrbHostCompose
import com.jarvis.assistant.ui.orb.JarvisOrbMotionState
import com.jarvis.assistant.ui.orb.OrbPresentationLabels
import com.jarvis.assistant.ui.orb.OrbRenderConfig
import com.jarvis.assistant.ui.theme.JarvisSpacing
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.ui.theme.LocalJarvisColors
import com.jarvis.assistant.vision.ScreenCaptureManager
import java.util.Calendar
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Uncluttered dark-first Jetpack Compose Companion Home workspace.
 * Surfaces identity greeting, dominant procedural GPU orb, concise ambient/mission context,
 * actionable error states, and clean bottom actions without exposing raw telemetry on Home.
 */
@Composable
fun CompanionHomeScreen(
    viewModel: MainViewModel,
    preferences: AppPreferences,
    onOpenChat: () -> Unit,
    onOpenVoiceMode: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOrbStudio: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onMicOrOrbTap: () -> Unit,
    onMicOrOrbHold: () -> Unit,
    onFixLiveError: (JarvisLiveError) -> Unit,
    onDismissLiveError: () -> Unit
) {
    val context = LocalContext.current
    val isSessionOn by viewModel.isSessionOn.collectAsStateWithLifecycle()
    val isMicMuted by viewModel.isMicMuted.collectAsStateWithLifecycle()
    val conversationState by viewModel.conversationState.collectAsStateWithLifecycle()
    val isStandby by JarvisConversationService.isStandby.collectAsStateWithLifecycle()
    val voicePresentation by JarvisConversationService.voicePresentation.collectAsStateWithLifecycle()
    val audioFreq by viewModel.audioFrequency.collectAsStateWithLifecycle()
    val liveError by viewModel.liveError.collectAsStateWithLifecycle()
    val personalityName by viewModel.personalityName.collectAsStateWithLifecycle()
    val activeMission by MissionEngine.activeMission.collectAsStateWithLifecycle()
    val activeTask by AgentTaskEngine.taskState.collectAsStateWithLifecycle()
    val screenSharing by ScreenCaptureManager.getInstance().isSharing.collectAsStateWithLifecycle()
    val screenHasFrame by ScreenCaptureManager.getInstance().hasReceivedFrame.collectAsStateWithLifecycle()

    var ambientContextLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        JarvisEventBus.events
            .filterIsInstance<JarvisEvent.EnvironmentChanged>()
            .collect { ev ->
                val pct = (ev.confidence * 100f).toInt().coerceIn(0, 100)
                ambientContextLabel = "${ev.label} • $pct%"
            }
    }

    val renderConfig = remember(preferences.orbPreset, preferences.orbColorTheme, preferences.orbEyeOptions) {
        OrbRenderConfig.fromPreferences(
            orbPresetRaw = preferences.orbPreset,
            colorThemeRaw = preferences.orbColorTheme,
            eyeOptionsJson = preferences.orbEyeOptions
        )
    }

    val motionState = remember(isSessionOn, isStandby, isMicMuted, conversationState, voicePresentation) {
        JarvisOrbMotionState.resolve(
            isSessionOn = isSessionOn,
            isStandby = isStandby,
            isMicMuted = isMicMuted,
            conversationState = conversationState,
            snapshot = voicePresentation
        )
    }

    val userName = remember(preferences.userName) {
        preferences.userName.ifBlank { "NAZIB" }
    }
    val greeting = remember(userName) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val period = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        "$period, $userName"
    }

    JarvisTheme(themeKey = preferences.appTheme, customHex = preferences.customThemeColor) {
        val colors = LocalJarvisColors.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .padding(horizontal = JarvisSpacing.md)
                .padding(top = JarvisSpacing.sm, bottom = 92.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${personalityName.ifBlank { "Jarvis" }} • ${renderConfig.preset.displayName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
                        HeaderPillButton(label = "Studio", onClick = onOpenOrbStudio)
                        HeaderPillButton(label = "Engine", onClick = onOpenDiagnostics)
                        HeaderPillButton(label = "Prefs", onClick = onOpenSettings)
                    }
                }

                // Ambient acoustic scene & active mission context (only shown when relevant)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = JarvisSpacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
                ) {
                    ambientContextLabel?.let { scene ->
                        Surface(
                            color = colors.surfaceCard,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, colors.cardBorder)
                        ) {
                            Text(
                                text = "Ambient: $scene",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }

                    val showMission = activeMission != null && activeMission?.status in setOf(
                        MissionOverallStatus.EXECUTING,
                        MissionOverallStatus.VERIFYING,
                        MissionOverallStatus.PAUSED
                    )
                    if (showMission && activeMission != null) {
                        val done = activeMission!!.steps.count { it.status.name == "COMPLETED" }
                        Surface(
                            color = colors.surfaceCard,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(JarvisSpacing.sm)) {
                                Text(
                                    text = "Mission Active • $done/${activeMission!!.steps.size} steps",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.accent
                                )
                                Text(
                                    text = activeMission!!.goal,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else if (activeTask != null) {
                        val doneSteps = activeTask!!.steps.count { it.status == TaskStepStatus.COMPLETED }
                        Surface(
                            color = colors.surfaceCard,
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, colors.accentSecondary.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(JarvisSpacing.sm)) {
                                Text(
                                    text = "Agent • ${activeTask!!.progressPercentage}% ($doneSteps/${activeTask!!.steps.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.accent
                                )
                                Text(
                                    text = activeTask!!.originalGoal,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Hero GPU Orb Centerpiece
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val accessibilityDesc = remember(voicePresentation, motionState) {
                        OrbPresentationLabels.status(context, voicePresentation)
                    }
                    JarvisOrbHostCompose(
                        config = renderConfig,
                        motionState = motionState,
                        rms = audioFreq.amplitude,
                        bass = audioFreq.bass,
                        mid = audioFreq.mid,
                        treble = audioFreq.high,
                        accessibilityLabel = accessibilityDesc,
                        onTap = onMicOrOrbTap,
                        onLongPress = onMicOrOrbHold,
                        modifier = Modifier
                            .fillMaxWidth(0.76f)
                            .heightIn(min = 210.dp, max = 300.dp)
                            .aspectRatio(1f)
                    )

                    Spacer(modifier = Modifier.height(JarvisSpacing.xs))

                    val (statusLine, hintLine, dotColor) = remember(
                        isSessionOn, isMicMuted, isStandby, conversationState
                    ) {
                        resolveStatusCopy(
                            isSessionOn = isSessionOn,
                            isMicMuted = isMicMuted,
                            isStandby = isStandby,
                            state = conversationState,
                            accent = colors.accent,
                            green = colors.statusGreen,
                            amber = colors.statusAmber,
                            red = colors.statusRed,
                            pink = colors.statusPink,
                            muted = colors.textMuted
                        )
                    }

                    Surface(
                        color = colors.surfaceCard,
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, dotColor.copy(alpha = 0.45f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusLine,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = hintLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted
                    )
                }

                // Bottom Action Strip + Primary Voice Controls
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
                    ) {
                        QuickActionPill(
                            title = "Voice",
                            subtitle = "Full Screen",
                            active = false,
                            onClick = onOpenVoiceMode,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionPill(
                            title = "Chat",
                            subtitle = "Messages",
                            active = false,
                            onClick = onOpenChat,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionPill(
                            title = "Screen",
                            subtitle = when {
                                !screenSharing -> "Share"
                                !screenHasFrame -> "Starting"
                                else -> "Live"
                            },
                            active = screenSharing && screenHasFrame,
                            onClick = onToggleScreenShare,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionPill(
                            title = "Tools",
                            subtitle = "Hub",
                            active = false,
                            onClick = onOpenTools,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)
                    ) {
                        val primaryLabel = when {
                            !isSessionOn -> "Start Jarvis"
                            conversationState == ConversationState.SPEAKING -> "Interrupt"
                            isMicMuted -> "Unmute Mic"
                            else -> "Mute Mic"
                        }
                        Button(
                            onClick = onMicOrOrbTap,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isSessionOn) colors.accent else colors.surfaceCard,
                                contentColor = if (!isSessionOn) colors.background else colors.textPrimary
                            ),
                            border = if (isSessionOn) BorderStroke(1.dp, colors.accent.copy(alpha = 0.6f)) else null,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(vertical = 13.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = primaryLabel, style = MaterialTheme.typography.titleMedium)
                        }

                        if (isSessionOn) {
                            OutlinedButton(
                                onClick = onMicOrOrbHold,
                                border = BorderStroke(1.dp, colors.statusRed.copy(alpha = 0.7f)),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(vertical = 13.dp, horizontal = 18.dp)
                            ) {
                                Text(
                                    text = "End",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.statusRed
                                )
                            }
                        }
                    }
                }
            }

            // Actionable Error Overlay (shown only when an active error exists)
            val err = liveError
            if (err != null) {
                Surface(
                    color = colors.surface,
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, colors.statusRed),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = JarvisSpacing.sm)
                ) {
                    Column(modifier = Modifier.padding(JarvisSpacing.md)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = err.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.statusRed,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissLiveError) {
                                Text("Dismiss", color = colors.textMuted)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = err.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(JarvisSpacing.sm))
                        Button(
                            onClick = { onFixLiveError(err) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.statusRed)
                        ) {
                            Text(err.actionText, color = colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderPillButton(
    label: String,
    onClick: () -> Unit
) {
    val colors = LocalJarvisColors.current
    Surface(
        color = colors.surfaceCard,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colors.cardBorder),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun QuickActionPill(
    title: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalJarvisColors.current
    Surface(
        color = if (active) colors.accent.copy(alpha = 0.16f) else colors.surfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (active) colors.accent else colors.cardBorder
        ),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) colors.accent else colors.textPrimary,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) colors.statusGreen else colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun resolveStatusCopy(
    isSessionOn: Boolean,
    isMicMuted: Boolean,
    isStandby: Boolean,
    state: ConversationState,
    accent: androidx.compose.ui.graphics.Color,
    green: androidx.compose.ui.graphics.Color,
    amber: androidx.compose.ui.graphics.Color,
    red: androidx.compose.ui.graphics.Color,
    pink: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color
): Triple<String, String, androidx.compose.ui.graphics.Color> {
    if (!isSessionOn) {
        return Triple("TAP ORB TO START", "Hold orb for power • Studio to customize", muted)
    }
    if (isMicMuted) {
        return Triple("MIC MUTED", "Tap orb or button to unmute", red)
    }
    if (isStandby || state == ConversationState.STANDBY) {
        return Triple("STANDBY • SAY 'HEY JARVIS'", "Listening for 3 wake phrases", accent)
    }
    return when (state) {
        ConversationState.CONNECTING -> Triple("CONNECTING TO GEMINI...", "Establishing voice session", amber)
        ConversationState.CONNECTED, ConversationState.LISTENING, ConversationState.INTERRUPTED ->
            Triple("LISTENING...", "Speak naturally in EN, HI, or Hinglish", accent)
        ConversationState.SPEAKING -> Triple("JARVIS SPEAKING", "Tap orb anytime to interrupt", pink)
        ConversationState.THINKING, ConversationState.EXECUTING, ConversationState.RECOVERING ->
            Triple("THINKING...", "Processing response", amber)
        ConversationState.WAITING_FOR_APPROVAL -> Triple("WAITING FOR APPROVAL", "Review action confirmation", amber)
        ConversationState.ERROR, ConversationState.FAILED -> Triple("NEEDS ATTENTION", "Tap Fix or check connection", red)
        ConversationState.IDLE -> Triple("READY", "Speak now or hold orb to power off", green)
        ConversationState.STANDBY -> Triple("STANDBY", "Listening for wake phrase", accent)
    }
}
