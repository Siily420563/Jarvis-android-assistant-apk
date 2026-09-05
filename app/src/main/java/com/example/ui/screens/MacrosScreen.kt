package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.MacroCache
import com.example.engine.TaskPlan
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun MacrosScreen(
    viewModel: MainViewModel,
    onNavigateToHome: () -> Unit
) {
    val macros by viewModel.cachedMacros.collectAsState(initial = emptyList())
    val isRecording by viewModel.isMacroRecording.collectAsState()
    var macroNameInput by remember { mutableStateOf("") }
    var tapLabelInput by remember { mutableStateOf("Target Button") }
    var tapXInput by remember { mutableStateOf("500") }
    var tapYInput by remember { mutableStateOf("1000") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = "Macros",
                    tint = SaraPink,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "AUTOMATION MACROS",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Self-learning cached execution pipelines",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    tint = SaraPink,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "When SARA completes a multi-step task (e.g. WhatsApp, Navigation), it caches the verified task graph so subsequent executions run instantly with zero latency!",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Teach-by-Demo (Macro v2) Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CyberCardBg),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(if (isRecording) SaraPink else CyberCardBorder)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TEACH-BY-DEMO MACRO V2",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (isRecording) "RECORDING IN PROGRESS..." else "Record gestures & taps to replay instantly",
                            color = if (isRecording) SaraPink else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    Button(
                        onClick = {
                            if (isRecording) {
                                viewModel.stopMacroRecordingAndSave(macroNameInput)
                                macroNameInput = ""
                            } else {
                                viewModel.startMacroRecording()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFE53935) else NeonCyan
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isRecording) "STOP & SAVE" else "START RECORD",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) Color.White else CyberBg
                        )
                    }
                }

                if (isRecording) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = macroNameInput,
                        onValueChange = { macroNameInput = it },
                        label = { Text("Macro Name (e.g. Open Telegram Chat)", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SaraPink,
                            unfocusedBorderColor = CyberCardBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = tapLabelInput,
                            onValueChange = { tapLabelInput = it },
                            label = { Text("Tap Label", fontSize = 10.sp) },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberCardBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        OutlinedTextField(
                            value = tapXInput,
                            onValueChange = { tapXInput = it },
                            label = { Text("X", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberCardBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        OutlinedTextField(
                            value = tapYInput,
                            onValueChange = { tapYInput = it },
                            label = { Text("Y", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonCyan,
                                unfocusedBorderColor = CyberCardBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val x = tapXInput.toFloatOrNull() ?: 500f
                            val y = tapYInput.toFloatOrNull() ?: 1000f
                            viewModel.recordManualTap(tapLabelInput, x, y)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ElegantPurple),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "+ ADD DEMO TAP POINT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "CACHED AUTOMATIONS (${macros.size})",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (macros.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No automation macros cached yet.",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Execute any multi-step command on Home screen to build a macro.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                items(macros, key = { it.id }) { macro ->
                    MacroItemCard(
                        macro = macro,
                        onRun = {
                            viewModel.runMacroDirect(macro)
                            onNavigateToHome()
                        },
                        onDelete = { viewModel.deleteMacro(macro) }
                    )
                }
            }
        }
    }
}

@Composable
fun MacroItemCard(
    macro: MacroCache,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val plan = TaskPlan.fromJsonString(macro.taskGraphJson)
    val stepCount = plan?.steps?.size ?: 1

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("macro_item_${macro.id}"),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = SaraPink.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = macro.intentKey,
                        color = SaraPink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Text(
                    text = "RUN COUNT: ${macro.executionCount}",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "\"${macro.taskDescription}\"",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$stepCount pipeline automation steps",
                color = TextSecondary,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Macro",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onRun,
                    colors = ButtonDefaults.buttonColors(containerColor = SaraPink),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("EXECUTE MACRO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
