package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.InteractionLog
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onNavigateToBrain: () -> Unit
) {
    val recognizedText by viewModel.recognizedText.collectAsState()
    val jarvisResponse by viewModel.jarvisResponse.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAccessibilityOnline by viewModel.isAccessibilityOnline.collectAsState()
    val isOverlayAuthorized by viewModel.isOverlayAuthorized.collectAsState()
    val hasAnyKey by viewModel.hasAnyKey.collectAsState()
    val interactionLogs by viewModel.interactionLogs.collectAsState(initial = emptyList())

    var typedCommand by remember { mutableStateOf("") }
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Warning Banner if No API Key is set
        if (!hasAnyKey) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1014)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFEF4444))),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "CRITICAL REQUIREMENT: NO API KEY DETECTED",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "J.A.R.V.I.S. requires an API key (Groq, Gemini, or OpenRouter) to execute commands.",
                            color = TextPrimary,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onNavigateToBrain,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("CONFIGURE API KEY IN BRAIN TAB >", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Central Arc Reactor Sphere
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                ArcReactorView(
                    isListening = isListening,
                    isProcessing = isProcessing,
                    onClick = {
                        if (isListening) viewModel.stopListening() else viewModel.startListening()
                    }
                )
            }
        }

        // Status Sub-Terminal Response Box
        item {
            StatusSubTerminal(
                recognizedText = recognizedText,
                jarvisResponse = jarvisResponse,
                isListening = isListening,
                isProcessing = isProcessing
            )
        }

        // Direct Typed Command Input Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberCardBg, RoundedCornerShape(16.dp))
                    .border(1.dp, CyberCardBorder, RoundedCornerShape(16.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = typedCommand,
                    onValueChange = { typedCommand = it },
                    placeholder = { Text("Type command to J.A.R.V.I.S....", color = TextSecondary, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        if (typedCommand.isNotBlank()) {
                            viewModel.executeCommand(typedCommand)
                            typedCommand = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(NeonCyan, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = CyberBg,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Quick Action Preset Chips
        item {
            Column {
                Text(
                    text = "RAPID PRESET COMMANDS",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                QuickActionChips(onChipClick = { viewModel.executeCommand(it) })
            }
        }

        // System Access Permission Status Cards
        item {
            SystemAccessCards(
                isAccessibilityOnline = isAccessibilityOnline,
                isOverlayAuthorized = isOverlayAuthorized,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenOverlaySettings = {
                    if (isOverlayAuthorized) {
                        viewModel.toggleFloatingBubble(context)
                    } else {
                        onOpenOverlaySettings()
                    }
                }
            )
        }

        // Neural Conversation Log Stream
        item {
            Text(
                text = "NEURAL LOG STREAM",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (interactionLogs.isEmpty()) {
            item {
                Text(
                    text = "No interaction logs recorded yet, Sir.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(interactionLogs.take(15)) { log ->
                LogItemCard(log = log)
            }
        }
    }
}

@Composable
fun LogItemCard(log: InteractionLog) {
    val isUser = log.isUser
    val cardBg = if (isUser) CyberCardBg else Color(0xFF0D1D2D)
    val borderColor = if (isUser) CyberCardBorder else NeonCyan.copy(alpha = 0.5f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(borderColor)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isUser) "BOSS" else "J.A.R.V.I.S.",
                    color = if (isUser) ElegantPurple else NeonCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp)),
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = log.text,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
