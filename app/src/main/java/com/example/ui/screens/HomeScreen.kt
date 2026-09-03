package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.InteractionLog
import com.example.engine.InterruptedTaskState
import com.example.persona.PersonaType
import com.example.ui.MainViewModel
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onRequestMicPermission: () -> Unit = {},
    onRequestContactsPermission: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onNavigateToBrain: () -> Unit
) {
    val recognizedText by viewModel.recognizedText.collectAsState()
    val saraResponse by viewModel.saraResponse.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isAccessibilityOnline by viewModel.isAccessibilityOnline.collectAsState()
    val isOverlayAuthorized by viewModel.isOverlayAuthorized.collectAsState()
    val isMicGranted by viewModel.isMicGranted.collectAsState()
    val isBatteryExempted by viewModel.isBatteryExempted.collectAsState()
    val isPhoneContactsGranted by viewModel.isPhoneContactsGranted.collectAsState()
    val hasAnyKey by viewModel.hasAnyKey.collectAsState()
    val activePersona by viewModel.activePersona.collectAsState()
    val currentTaskPlan by viewModel.currentTaskPlan.collectAsState()
    val pendingRiskyPlan by viewModel.pendingRiskyPlan.collectAsState()
    val interruptedTask by viewModel.interruptedTask.collectAsState()
    val interactionLogs by viewModel.interactionLogs.collectAsState(initial = emptyList())

    var typedCommand by remember { mutableStateOf("") }
    val context = LocalContext.current

    val onOrbClick = {
        if (!isMicGranted) {
            onRequestMicPermission()
        } else {
            viewModel.toggleContinuousOrbMode()
        }
    }

    val onChatMicClick = {
        if (isListening) {
            viewModel.stopListening()
        } else {
            if (!isMicGranted) {
                onRequestMicPermission()
            } else {
                viewModel.startSingleTurnMic { recognized ->
                    typedCommand = recognized
                }
            }
        }
    }

    // Confirmation dialog for payments or destructive actions
    if (pendingRiskyPlan != null) {
        RiskyActionConfirmationDialog(
            plan = pendingRiskyPlan,
            onConfirm = { viewModel.confirmPendingRiskyPlan(true) },
            onCancel = { viewModel.confirmPendingRiskyPlan(false) }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Persona Quick-Badge & Model Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = when (activePersona) {
                            PersonaType.GIRLFRIEND -> SaraPink.copy(alpha = 0.2f)
                            PersonaType.PROFESSIONAL -> NeonCyan.copy(alpha = 0.2f)
                            PersonaType.BOLD -> Color(0xFFF97316).copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(20.dp),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(
                                when (activePersona) {
                                    PersonaType.GIRLFRIEND -> SaraPink
                                    PersonaType.PROFESSIONAL -> NeonCyan
                                    PersonaType.BOLD -> Color(0xFFF97316)
                                }
                            )
                        ),
                        modifier = Modifier.clickable { onNavigateToBrain() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = when (activePersona) {
                                    PersonaType.GIRLFRIEND -> "💕 ${activePersona.displayName}"
                                    PersonaType.PROFESSIONAL -> "👔 ${activePersona.displayName}"
                                    PersonaType.BOLD -> "🔥 ${activePersona.displayName}"
                                },
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Surface(
                    color = CyberCardBg,
                    shape = RoundedCornerShape(20.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)
                    )
                ) {
                    Text(
                        text = if (hasAnyKey) "AI CORE: ONLINE" else "HEURISTIC MODE",
                        color = if (hasAnyKey) StatusOnline else Color(0xFFF59E0B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Stop button - interrupts and pauses whatever SARA is currently doing
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = Color(0xFFEF4444).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFEF4444))
                    ),
                    modifier = Modifier
                        .clickable { viewModel.stopSaraNow() }
                        .testTag("sara_stop_btn")
                ) {
                    Text(
                        text = "⏹ STOP",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Interrupted Task Card (Phase 1 Multi-turn Context & Resumption)
        val taskState = interruptedTask
        if (taskState != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("interrupted_task_card"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Color(0xFF818CF8))
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⏸", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "TASK PAUSED / INTERRUPTED",
                                    color = Color(0xFF818CF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            Text(
                                text = "Step ${taskState.stoppedStepIndex + 1}/${taskState.plan.steps.size}",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = taskState.plan.originalQuery,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = "Bolo 'continue' to resume, or give a new instruction (e.g. 'nahi papa ko') to merge context.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { viewModel.dismissInterruptedTask() },
                                modifier = Modifier.testTag("dismiss_task_btn")
                            ) {
                                Text("DISMISS", color = TextSecondary, fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { viewModel.resumeInterruptedTask() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("resume_task_btn")
                            ) {
                                Text("RESUME TASK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Central SARA Voice Orb
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                ArcReactorView(
                    isListening = isListening,
                    isProcessing = isProcessing,
                    persona = activePersona,
                    onClick = onOrbClick
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isListening) "🎤 Continuous Listening Active (Tap Orb to Pause)" else if (isProcessing) "⚡ Processing Neural Automation..." else "✨ Tap Orb for Continuous Hands-Free Voice • Or Speak Below",
                    color = when {
                        isListening -> Color(0xFFFF5252)
                        isProcessing -> NeonCyan
                        else -> TextSecondary
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Live Sub-Terminal / Spoken Text Card
        item {
            StatusSubTerminal(
                recognizedText = recognizedText,
                jarvisResponse = saraResponse,
                isListening = isListening,
                isProcessing = isProcessing
            )
        }

        // Live Multi-Step Execution Pipeline Card
        if (currentTaskPlan != null && currentTaskPlan!!.steps.isNotEmpty()) {
            item {
                TaskExecutionCard(plan = currentTaskPlan)
            }
        }

        // Direct Typed Command Input Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberCardBg, RoundedCornerShape(16.dp))
                    .border(1.dp, CyberCardBorder, RoundedCornerShape(16.dp))
                    .padding(4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onChatMicClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = if (isListening) Color(0xFFFF5252) else when (activePersona) {
                            PersonaType.GIRLFRIEND -> SaraPink
                            PersonaType.PROFESSIONAL -> NeonCyan
                            PersonaType.BOLD -> Color(0xFFF97316)
                        }
                    )
                }

                OutlinedTextField(
                    value = typedCommand,
                    onValueChange = { typedCommand = it },
                    placeholder = { Text("Command SARA in Hinglish...", color = TextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (typedCommand.isNotBlank()) {
                                viewModel.executeUserCommand(typedCommand)
                                typedCommand = ""
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sara_command_input")
                )

                IconButton(
                    onClick = {
                        if (typedCommand.isNotBlank()) {
                            viewModel.executeUserCommand(typedCommand)
                            typedCommand = ""
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            when (activePersona) {
                                PersonaType.GIRLFRIEND -> SaraPink
                                PersonaType.PROFESSIONAL -> NeonCyan
                                PersonaType.BOLD -> Color(0xFFF97316)
                            },
                            CircleShape
                        )
                        .testTag("sara_send_command_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Command",
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
                    text = "RAPID HINGLISH COMMANDS",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                QuickActionChips(onChipClick = { viewModel.executeUserCommand(it) })
            }
        }

        // Step-by-Step Onboarding Permissions Walkthrough
        item {
            OnboardingPermissionsCard(
                isAccessibilityOnline = isAccessibilityOnline,
                isOverlayAuthorized = isOverlayAuthorized,
                isMicGranted = isMicGranted,
                isBatteryExempted = isBatteryExempted,
                isContactsGranted = isPhoneContactsGranted,
                onRequestMic = onRequestMicPermission,
                onRequestContacts = onRequestContactsPermission
            )
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

        // Neural Interaction Stream
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "INTERACTION STREAM",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                // NEW: Clear chat button - clears the visible log only, memory stays intact
                Text(
                    text = "Clear",
                    color = Color(0xFFEF4444),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { viewModel.clearChatHistory() }
                        .testTag("sara_clear_chat_btn")
                )
            }
        }

        if (interactionLogs.isEmpty()) {
            item {
                Text(
                    text = "Koi interaction log nahi hai abhi tak. Speak or type above!",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(interactionLogs.take(20)) { log ->
                LogItemCard(log = log, persona = activePersona)
            }
        }
    }
}

@Composable
fun LogItemCard(log: InteractionLog, persona: PersonaType) {
    val isUser = log.isUser
    val cardBg = if (isUser) CyberCardBg else Color(0xFF0F172A)
    val borderColor = if (isUser) CyberCardBorder else when (persona) {
        PersonaType.GIRLFRIEND -> SaraPink.copy(alpha = 0.5f)
        PersonaType.PROFESSIONAL -> NeonCyan.copy(alpha = 0.5f)
        PersonaType.BOLD -> Color(0xFFF97316).copy(alpha = 0.5f)
    }

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
                    text = if (isUser) "YOU" else "SARA",
                    color = if (isUser) ElegantPurple else when (persona) {
                        PersonaType.GIRLFRIEND -> SaraPink
                        PersonaType.PROFESSIONAL -> NeonCyan
                        PersonaType.BOLD -> Color(0xFFF97316)
                    },
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
