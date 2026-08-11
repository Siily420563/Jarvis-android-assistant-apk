package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCardBg
import com.example.ui.theme.CyberCardBorder
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun StatusSubTerminal(
    recognizedText: String,
    jarvisResponse: String,
    isListening: Boolean,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CyberCardBg, RoundedCornerShape(16.dp))
            .border(1.dp, CyberCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "NEURAL VOCAL MATRIX",
                color = NeonCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace
            )

            val statusStr = when {
                isListening -> "LISTENING..."
                isProcessing -> "PROCESSING..."
                else -> "STANDBY"
            }
            val statusColor = when {
                isListening -> Color(0xFFFF5252)
                isProcessing -> NeonCyan
                else -> Color(0xFF10B981)
            }

            Text(
                text = statusStr,
                color = statusColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (recognizedText.isNotBlank()) {
            Text(
                text = "Boss: \"$recognizedText\"",
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Default
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = if (jarvisResponse.isNotBlank()) jarvisResponse else "Awaiting your vocal or typed command, Sir...",
            color = TextPrimary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
