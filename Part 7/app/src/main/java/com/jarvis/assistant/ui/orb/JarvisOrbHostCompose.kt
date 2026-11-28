package com.jarvis.assistant.ui.orb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.core.runtime.ResourceGovernor
import com.jarvis.assistant.util.VisualMotionSettings

/**
 * Unified Compose orb container binding real-time hardware OpenGL ES 3.0 shaders
 * under ResourceGovernor FPS budgeting, with reduced-motion and Preview safety.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JarvisOrbHostCompose(
    config: OrbRenderConfig,
    motionState: JarvisOrbMotionState,
    rms: Float,
    bass: Float,
    mid: Float,
    treble: Float,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current
    val policy by ResourceGovernor.policy.collectAsStateWithLifecycle()
    val reducedMotion = remember(context) {
        if (isPreview) true else VisualMotionSettings.isReduced(context)
    }

    val clickableModifier = if (onTap != null || onLongPress != null) {
        val source = remember { MutableInteractionSource() }
        modifier.combinedClickable(
            interactionSource = source,
            indication = null,
            onClick = { onTap?.invoke() },
            onLongClick = onLongPress
        )
    } else {
        modifier
    }

    Box(
        modifier = clickableModifier.semantics {
            contentDescription = accessibilityLabel
        }
    ) {
        if (isPreview || reducedMotion) {
            StaticAccessibleOrbCanvas(
                config = config,
                motionState = motionState,
                rms = rms,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val lifecycleOwner = LocalLifecycleOwner.current
            val glView = remember(context) { JarvisOrbGLSurfaceView(context) }

            DisposableEffect(lifecycleOwner, glView) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> glView.onResume()
                        Lifecycle.Event.ON_PAUSE -> glView.onPause()
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    glView.onResume()
                }
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    glView.onPause()
                }
            }

            AndroidView(
                factory = { glView },
                update = { view ->
                    view.updateFrameBudget(policy.maxOrbFps, isReducedMotion = false)
                    view.submitState(config, motionState, rms, bass, mid, treble)
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun StaticAccessibleOrbCanvas(
    config: OrbRenderConfig,
    motionState: JarvisOrbMotionState,
    rms: Float,
    modifier: Modifier = Modifier
) {
    val p1 = Color(config.colorTheme.primaryArgb)
    val p2 = Color(config.colorTheme.secondaryArgb)
    val p3 = Color(config.colorTheme.coreArgb)
    val stateAccent = when (motionState) {
        JarvisOrbMotionState.SPEAKING -> Color(0xFFEC4899)
        JarvisOrbMotionState.THINKING -> Color(0xFFF59E0B)
        JarvisOrbMotionState.ERROR -> Color(0xFFEF4444)
        else -> p2
    }
    Canvas(modifier = modifier) {
        val baseRadius = (size.minDimension * 0.34f * config.scale * (1f + 0.08f * rms.coerceIn(0f, 1f)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(stateAccent.copy(alpha = 0.32f), p1.copy(alpha = 0.08f), Color.Transparent),
                center = center,
                radius = baseRadius * 1.55f
            ),
            center = center,
            radius = baseRadius * 1.55f
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(p3, stateAccent, p1),
                center = Offset(center.x - baseRadius * 0.2f, center.y - baseRadius * 0.2f),
                radius = baseRadius * 1.1f
            ),
            center = center,
            radius = baseRadius
        )
    }
}
