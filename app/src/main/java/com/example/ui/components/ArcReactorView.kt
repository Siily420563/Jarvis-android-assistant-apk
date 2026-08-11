package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonCyanGlow

@Composable
fun ArcReactorView(
    isListening: Boolean,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ArcReactorRotation")

    // Rotation animation for outer rings
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Pulse animation when listening or processing
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening || isProcessing) 1.25f else 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 600 else 1800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .size(220.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(200.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.width / 2) * pulseScale

            // Glowing Radial Gradient Background
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        NeonCyan.copy(alpha = if (isListening) 0.5f else 0.25f),
                        NeonCyanGlow,
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )

            // Outer Ring
            drawCircle(
                color = NeonCyan.copy(alpha = 0.8f),
                radius = radius * 0.8f,
                style = Stroke(width = 3.dp.toPx())
            )

            // Segmented Inner Ring
            rotate(rotationAngle) {
                for (i in 0 until 12) {
                    val angle = i * 30f
                    val strokeW = if (i % 3 == 0) 4.dp.toPx() else 2.dp.toPx()
                    drawArc(
                        color = NeonCyan,
                        startAngle = angle,
                        sweepAngle = 18f,
                        useCenter = false,
                        style = Stroke(width = strokeW)
                    )
                }
            }

            // Reverse Inner Ring
            rotate(-rotationAngle * 1.5f) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = radius * 0.55f,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Core Energy Center
            drawCircle(
                color = if (isListening) Color(0xFFFF5252) else NeonCyan,
                radius = radius * 0.35f
            )

            drawCircle(
                color = Color.White,
                radius = radius * 0.20f
            )
        }

        // Center Icon
        Icon(
            imageVector = if (isProcessing) Icons.Default.GraphicEq else Icons.Default.Mic,
            contentDescription = "Voice Input Arc Reactor",
            tint = if (isListening) Color.White else Color(0xFF060F1A),
            modifier = Modifier.size(36.dp)
        )
    }
}
