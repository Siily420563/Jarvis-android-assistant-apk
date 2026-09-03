package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.persona.PersonaType
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun BrainScreen(viewModel: MainViewModel) {
    val prefs = viewModel.prefs
    val hasAnyKey by viewModel.hasAnyKey.collectAsState()
    val activePersona by viewModel.activePersona.collectAsState()
    val isAccessibilityOnline by viewModel.isAccessibilityOnline.collectAsState()
    val isOverlayAuthorized by viewModel.isOverlayAuthorized.collectAsState()
    val isBatteryExempted by viewModel.isBatteryExempted.collectAsState()

    var assistantName by remember { mutableStateOf(prefs.assistantName) }
    var selectedPersona by remember { mutableStateOf(activePersona) }
    var geminiKey by remember { mutableStateOf(prefs.geminiApiKey) }
    var geminiModel by remember { mutableStateOf(prefs.geminiModel) }
    var groqKey by remember { mutableStateOf(prefs.groqApiKey) }
    var groqModel by remember { mutableStateOf(prefs.groqModel) }
    var openRouterKey by remember { mutableStateOf(prefs.openRouterApiKey) }
    var openRouterModel by remember { mutableStateOf(prefs.openRouterModel) }
    var openAiKey by remember { mutableStateOf(prefs.openAiApiKey) }
    var openAiBaseUrl by remember { mutableStateOf(prefs.openAiBaseUrl) }
    var openAiModel by remember { mutableStateOf(prefs.openAiModel) }
    var preferredLlm by remember { mutableStateOf(prefs.preferredLlm) }

    var showGeminiPass by remember { mutableStateOf(false) }
    var showGroqPass by remember { mutableStateOf(false) }
    var showOpenRouterPass by remember { mutableStateOf(false) }
    var showOpenAiPass by remember { mutableStateOf(false) }

    var confirmRiskyActions by remember { mutableStateOf(prefs.confirmRiskyActions) }
    var maxAgentSteps by remember { mutableStateOf(prefs.maxAgentSteps) }
    var verboseVoiceFeedback by remember { mutableStateOf(prefs.verboseVoiceFeedback) }
    var protectedAppsSet by remember { mutableStateOf(prefs.protectedApps) }
    var newCustomAppPackage by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "SARA Brain",
                tint = SaraPink,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "SARA AI & PERSONA STUDIO",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Configure voice personalities, multi-provider LLMs & permissions",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // Assistant Name Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ASSISTANT NAME",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = assistantName,
                    onValueChange = { assistantName = it },
                    label = { Text("Name (e.g. SARA)") },
                    leadingIcon = { Icon(Icons.Default.Face, contentDescription = null, tint = SaraPink) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SaraPink,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("assistant_name_input")
                )
            }
        }

        // Persona Selection Cards
        Text(
            text = "SELECT SARA PERSONALITY MODE",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PersonaType.values().forEach { p ->
                val isSelected = selectedPersona == p
                val (accentColor, icon) = when (p) {
                    PersonaType.GIRLFRIEND -> Pair(SaraPink, "💕")
                    PersonaType.PROFESSIONAL -> Pair(NeonCyan, "👔")
                    PersonaType.BOLD -> Pair(Color(0xFFF97316), "🔥")
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("persona_card_${p.name}")
                        .clickable { selectedPersona = p },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) accentColor.copy(alpha = 0.12f) else CyberCardBg
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (isSelected) accentColor else CyberCardBorder
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = accentColor.copy(alpha = 0.25f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(icon, fontSize = 20.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = p.displayName,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = accentColor,
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(
                                            text = "ACTIVE",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = when (p) {
                                    PersonaType.GIRLFRIEND -> "Caring, sweet, romantic Hinglish, emotional support, cheerful companion."
                                    PersonaType.PROFESSIONAL -> "Crisp, concise, respectful, maximum enterprise execution precision."
                                    PersonaType.BOLD -> "Witty, bindass, sarcastic, direct & humorous unfiltered attitude."
                                },
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedPersona = p },
                            colors = RadioButtonDefaults.colors(selectedColor = accentColor)
                        )
                    }
                }
            }
        }

        // Multi-Provider LLM Fallback Cascade
        Text(
            text = "MULTI-PROVIDER LLM FALLBACK CASCADE (LATEST SMART MODELS)",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        // Gemini Key (Primary)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1. GOOGLE GEMINI (SMARTEST REASONING & VISION)",
                        color = SaraPink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        color = SaraPink.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "PRIMARY",
                            color = SaraPink,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    label = { Text("Gemini API Key") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = SaraPink) },
                    trailingIcon = {
                        TextButton(onClick = { showGeminiPass = !showGeminiPass }) {
                            Text(if (showGeminiPass) "HIDE" else "SHOW", fontSize = 11.sp, color = SaraPink)
                        }
                    },
                    visualTransformation = if (showGeminiPass) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SaraPink,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gemini_api_key_input")
                )

                Text("Smartest Gemini Model Selection (AI Studio):", color = TextSecondary, fontSize = 11.sp)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val geminiModels = listOf(
                        "gemini-3.5-flash" to "Gemini 3.5 Flash (Default)",
                        "gemini-3.5-flash-lite" to "Gemini 3.5 Flash Lite",
                        "gemini-3.1-flash-lite" to "Gemini 3.1 Flash Lite",
                        "gemini-3.1-pro-preview" to "Gemini 3.1 Pro Preview",
                        "gemini-3.7-flash" to "Gemini 3.7 Flash"
                    )
                    geminiModels.forEach { (id, label) ->
                        FilterChip(
                            selected = geminiModel == id,
                            onClick = { geminiModel = id },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SaraPink,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        }

        // Groq Key (Fallback 1)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "2. GROQ LPU (ULTRA-FAST INFERENCE)",
                        color = NeonCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        color = NeonCyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "FALLBACK 1",
                            color = NeonCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    label = { Text("Groq API Key") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan) },
                    trailingIcon = {
                        TextButton(onClick = { showGroqPass = !showGroqPass }) {
                            Text(if (showGroqPass) "HIDE" else "SHOW", fontSize = 11.sp, color = NeonCyan)
                        }
                    },
                    visualTransformation = if (showGroqPass) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("groq_api_key_input")
                )

                Text("Smartest Groq Model Selection:", color = TextSecondary, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val groqModels = listOf(
                        "llama-3.3-70b-versatile" to "llama-3.3-70b (Smartest)",
                        "deepseek-r1-distill-llama-70b" to "deepseek-r1-70b (Reasoning)"
                    )
                    groqModels.forEach { (id, label) ->
                        FilterChip(
                            selected = groqModel == id,
                            onClick = { groqModel = id },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = CyberBg
                            )
                        )
                    }
                }
            }
        }

        // OpenRouter Key (Fallback 2)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "3. OPENROUTER (MULTI-MODEL GATEWAY)",
                        color = NeonBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        color = NeonBlue.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "FALLBACK 2",
                            color = NeonBlue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = openRouterKey,
                    onValueChange = { openRouterKey = it },
                    label = { Text("OpenRouter API Key") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonBlue) },
                    trailingIcon = {
                        TextButton(onClick = { showOpenRouterPass = !showOpenRouterPass }) {
                            Text(if (showOpenRouterPass) "HIDE" else "SHOW", fontSize = 11.sp, color = NeonBlue)
                        }
                    },
                    visualTransformation = if (showOpenRouterPass) VisualTransformation.None else PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonBlue,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("openrouter_api_key_input")
                )

                Text("Smartest OpenRouter Model Selection:", color = TextSecondary, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val openRouterModels = listOf(
                        "anthropic/claude-3.7-sonnet" to "Claude 3.7 Sonnet (Smartest)",
                        "deepseek/deepseek-r1" to "DeepSeek R1",
                        "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B"
                    )
                    openRouterModels.forEach { (id, label) ->
                        FilterChip(
                            selected = openRouterModel == id,
                            onClick = { openRouterModel = id },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonBlue,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }
            }
        }

        // 4. OpenAI / Compatible Endpoint Card (Custom / Ollama / Local / vLLM / OpenAI)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OPENAI / COMPATIBLE ENDPOINT",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = if (openAiKey.isNotBlank()) "CONFIGURED" else "OPTIONAL",
                        color = if (openAiKey.isNotBlank()) StatusOnline else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = openAiBaseUrl,
                    onValueChange = { openAiBaseUrl = it },
                    label = { Text("Base URL (e.g. https://api.openai.com/v1)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = openAiKey,
                    onValueChange = { openAiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = if (showOpenAiPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showOpenAiPass = !showOpenAiPass }) {
                            Icon(
                                imageVector = if (showOpenAiPass) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Visibility",
                                tint = TextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = openAiModel,
                    onValueChange = { openAiModel = it },
                    label = { Text("Model Name (e.g. gpt-4o-mini, deepseek-chat)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberCardBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Preferred Engine Choice
        Text(
            text = "PREFERRED ENGINE DISPATCH",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val options = listOf("AUTO", "GEMINI", "GROQ", "OPENAI", "OPENROUTER")
            options.forEach { opt ->
                FilterChip(
                    selected = preferredLlm == opt,
                    onClick = { preferredLlm = opt },
                    label = { Text(opt, fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SaraPink,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        // Autonomous Agent & Safety Configuration Card
        Text(
            text = "AUTONOMOUS AGENT & SAFETY POLICY",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Confirm Risky Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Confirm Risky Actions", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Prompts user confirmation before financial apps, uninstall, or settings reset", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = confirmRiskyActions,
                        onCheckedChange = { confirmRiskyActions = it }
                    )
                }

                HorizontalDivider(color = CyberCardBorder)

                // Verbose Voice Feedback
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Speak Action Steps", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Announces what Jarvis is doing on screen at each autonomous step", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = verboseVoiceFeedback,
                        onCheckedChange = { verboseVoiceFeedback = it }
                    )
                }

                HorizontalDivider(color = CyberCardBorder)

                // Max Agent Autonomous Steps
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Max Autonomous ReAct Steps", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("$maxAgentSteps steps", color = NeonCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = maxAgentSteps.toFloat(),
                        onValueChange = { maxAgentSteps = it.toInt() },
                        valueRange = 5f..50f,
                        steps = 8,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }

                HorizontalDivider(color = CyberCardBorder)

                // Protected Apps List
                Column {
                    Text("Protected Sensitive Apps (Requires Confirmation)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val defaultApps = listOf("phonepe", "paytm", "gpay", "bhim", "cred")
                        defaultApps.forEach { appKey ->
                            val isProtected = protectedAppsSet.contains(appKey)
                            FilterChip(
                                selected = isProtected,
                                onClick = {
                                    protectedAppsSet = if (isProtected) {
                                        protectedAppsSet - appKey
                                    } else {
                                        protectedAppsSet + appKey
                                    }
                                },
                                label = { Text(appKey.uppercase(), fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFEF4444),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // System Permissions & Battery Optimization
        Text(
            text = "SYSTEM POWER & AUTOMATION PERMISSIONS",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Battery Optimization
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Battery Optimization Exemption",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isBatteryExempted) "Exempted (Background persistence active)" else "Required so Android OS doesn't kill SARA",
                            color = if (isBatteryExempted) StatusOnline else StatusWarning,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = { viewModel.requestBatteryOptimization(context) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isBatteryExempted) Color(0xFF1E293B) else SaraPink
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("battery_exemption_btn")
                    ) {
                        Text(
                            text = if (isBatteryExempted) "EXEMPTED" else "REQUEST",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                HorizontalDivider(color = CyberCardBorder)

                // Accessibility Service
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accessibility Screen Automation",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isAccessibilityOnline) "ONLINE (Taps, scrolls & typing active)" else "OFFLINE (Enable in Accessibility Settings)",
                            color = if (isAccessibilityOnline) StatusOnline else StatusOffline,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAccessibilityOnline) Color(0xFF1E293B) else NeonCyan
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isAccessibilityOnline) "ACTIVE" else "ENABLE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAccessibilityOnline) Color.White else CyberBg
                        )
                    }
                }

                HorizontalDivider(color = CyberCardBorder)

                // Floating Overlay Bubble
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Floating Voice Bubble",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isOverlayAuthorized) "Authorized (Overlay over other apps)" else "Disabled (Grant overlay permission)",
                            color = if (isOverlayAuthorized) StatusOnline else StatusOffline,
                            fontSize = 11.sp
                        )
                    }
                    Button(
                        onClick = {
                            if (isOverlayAuthorized) {
                                viewModel.toggleFloatingBubble(context)
                            } else {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElegantPurple),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isOverlayAuthorized) "TOGGLE BUBBLE" else "GRANT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Save Button
        Button(
            onClick = {
                viewModel.saveSettings(
                    geminiKey = geminiKey,
                    groqKey = groqKey,
                    openRouterKey = openRouterKey,
                    preferredLlm = preferredLlm,
                    assistantName = assistantName,
                    persona = selectedPersona,
                    geminiModel = geminiModel,
                    groqModel = groqModel,
                    openRouterModel = openRouterModel,
                    openAiKey = openAiKey,
                    openAiBaseUrl = openAiBaseUrl,
                    openAiModel = openAiModel,
                    protectedApps = protectedAppsSet,
                    maxAgentSteps = maxAgentSteps,
                    confirmRiskyActions = confirmRiskyActions,
                    verboseVoiceFeedback = verboseVoiceFeedback
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = SaraPink),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("save_settings_btn")
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SAVE SARA SETTINGS & ACTIVATE",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
