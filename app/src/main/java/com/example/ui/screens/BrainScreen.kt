package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun BrainScreen(viewModel: MainViewModel) {
    val prefs = viewModel.prefs
    val hasAnyKey by viewModel.hasAnyKey.collectAsState()

    var groqKey by remember { mutableStateOf(prefs.groqApiKey) }
    var geminiKey by remember { mutableStateOf(prefs.geminiApiKey) }
    var openRouterKey by remember { mutableStateOf(prefs.openRouterApiKey) }
    var preferredLlm by remember { mutableStateOf(prefs.preferredLlm) }

    var showGroqPass by remember { mutableStateOf(false) }
    var showGeminiPass by remember { mutableStateOf(false) }
    var showOpenRouterPass by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = "Neural Core",
                tint = NeonCyan,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "NEURAL CORE & API KEYS",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (hasAnyKey) Color(0xFF0F2D20) else Color(0xFF3B1014)),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(if (hasAnyKey) StatusOnline else StatusOffline)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (hasAnyKey) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (hasAnyKey) StatusOnline else StatusOffline,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (hasAnyKey) "NEURAL PIPELINE ACTIVE" else "API KEY MANDATORY",
                        color = if (hasAnyKey) StatusOnline else StatusOffline,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (hasAnyKey)
                            "J.A.R.V.I.S. is ready to execute queries and system automation."
                        else
                            "No API Key provided. Please enter at least one key below for J.A.R.V.I.S. to function.",
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // API Key Input Fields
        Text(
            text = "PRIMARY LLM ENDPOINTS",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        // Groq Key
        OutlinedTextField(
            value = groqKey,
            onValueChange = { groqKey = it },
            label = { Text("Groq API Key (llama-3.3-70b)") },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonCyan) },
            trailingIcon = {
                TextButton(onClick = { showGroqPass = !showGroqPass }) {
                    Text(if (showGroqPass) "HIDE" else "SHOW", fontSize = 11.sp, color = NeonCyan)
                }
            },
            visualTransformation = if (showGroqPass) VisualTransformation.None else PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = CyberCardBorder),
            modifier = Modifier.fillMaxWidth()
        )

        // Gemini Key
        OutlinedTextField(
            value = geminiKey,
            onValueChange = { geminiKey = it },
            label = { Text("Gemini API Key (gemini-2.5-flash)") },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = ElegantPurple) },
            trailingIcon = {
                TextButton(onClick = { showGeminiPass = !showGeminiPass }) {
                    Text(if (showGeminiPass) "HIDE" else "SHOW", fontSize = 11.sp, color = ElegantPurple)
                }
            },
            visualTransformation = if (showGeminiPass) VisualTransformation.None else PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ElegantPurple, unfocusedBorderColor = CyberCardBorder),
            modifier = Modifier.fillMaxWidth()
        )

        // OpenRouter Key
        OutlinedTextField(
            value = openRouterKey,
            onValueChange = { openRouterKey = it },
            label = { Text("OpenRouter Key (llama-3-8b)") },
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = NeonBlue) },
            trailingIcon = {
                TextButton(onClick = { showOpenRouterPass = !showOpenRouterPass }) {
                    Text(if (showOpenRouterPass) "HIDE" else "SHOW", fontSize = 11.sp, color = NeonBlue)
                }
            },
            visualTransformation = if (showOpenRouterPass) VisualTransformation.None else PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonBlue, unfocusedBorderColor = CyberCardBorder),
            modifier = Modifier.fillMaxWidth()
        )

        // Preferred Engine Choice
        Text(
            text = "PREFERRED FALLBACK PRIORITY",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val options = listOf("AUTO", "GROQ", "GEMINI", "OPENROUTER")
            options.forEach { opt ->
                FilterChip(
                    selected = preferredLlm == opt,
                    onClick = { preferredLlm = opt },
                    label = { Text(opt, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonCyan,
                        selectedLabelColor = CyberBg
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                viewModel.saveApiKeys(groqKey, geminiKey, openRouterKey, preferredLlm)
            },
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("SAVE CREDENTIALS & ACTIVATE CORE", color = CyberBg, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
