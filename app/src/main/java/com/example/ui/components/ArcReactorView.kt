package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.persona.PersonaType
import com.example.ui.theme.*

@Composable
fun ArcReactorView(
    isListening: Boolean,
    isProcessing: Boolean,
    persona: PersonaType = PersonaType.GIRLFRIEND,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SaraOrbRotation")

    val (primaryColor, secondaryColor) = when (persona) {
        PersonaType.GIRLFRIEND -> Pair(SaraPink, SaraRose)
        PersonaType.PROFESSIONAL -> Pair(NeonCyan, NeonBlue)
        PersonaType.BOLD -> Pair(Color(0xFFF97316), Color(0xFFEF4444))
    }

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.25f else if (isProcessing) 1.18f else 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 500 else if (isProcessing) 800 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "pressScale"
    )

    Box(
        modifier = modifier
            .size(200.dp)
            .scale(pressScale)
            .testTag("sara_voice_orb")
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 90.dp, color = primaryColor)
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(190.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.width / 2) * pulseScale

            // Glowing Outer Atmospheric Halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (isListening) 0.6f else 0.25f),
                        secondaryColor.copy(alpha = if (isListening) 0.3f else 0.1f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )

            // Dynamic Concentric Ring 1
            drawCircle(
                color = primaryColor.copy(alpha = 0.8f),
                radius = radius * 0.78f,
                style = Stroke(width = 2.5.dp.toPx())
            )

            // Segmented Rotating Outer Crest
            rotate(rotationAngle) {
                for (i in 0 until 8) {
                    val angle = i * 45f
                    drawArc(
                        color = secondaryColor,
                        startAngle = angle,
                        sweepAngle = 24f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            // Counter Rotating Particle Ring
            rotate(-rotationAngle * 1.6f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.7f),
                    radius = radius * 0.52f,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Central Pulsing Core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        primaryColor,
                        secondaryColor
                    ),
                    center = center,
                    radius = radius * 0.36f
                ),
                radius = radius * 0.36f,
                center = center
            )
        }

        // Center Icon
        Icon(
            imageVector = if (isProcessing) Icons.Default.GraphicEq else Icons.Default.Mic,
            contentDescription = if (isListening) "Listening active" else "Tap to speak with SARA",
            tint = Color.White,
            modifier = Modifier.size(34.dp)
        )
    }
}
