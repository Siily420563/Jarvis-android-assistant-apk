package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.JarvisAlarm
import com.example.ui.MainViewModel
import com.example.ui.theme.*
import java.util.Locale

@Composable
fun AlarmsScreen(viewModel: MainViewModel) {
    val alarms by viewModel.jarvisAlarms.collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = "Alarms",
                        tint = NeonCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "FUTURISTIC ALARMS",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = NeonCyan,
                    contentColor = CyberBg,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Alarm")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Morning Briefing Test Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CyberCardBg),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyberCardBorder)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MORNING BRIEFING PROTOCOL",
                            color = NeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "J.A.R.V.I.S. automatically synthesizes your daily schedule & neural memories upon alarm dismissal.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.executeCommand("Good morning, give me my morning briefing")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberBg)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("TEST BRIEFING", fontSize = 10.sp, color = CyberBg, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "ACTIVE ALARM GRID",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (alarms.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active J.A.R.V.I.S. alarms scheduled, Sir.",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmItemCard(
                            alarm = alarm,
                            onToggle = { viewModel.toggleAlarmActive(alarm) },
                            onDelete = { viewModel.deleteAlarm(alarm) }
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AddAlarmDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { hour, minute, label ->
                    viewModel.addManualAlarm(hour, minute, label)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun AlarmItemCard(alarm: JarvisAlarm, onToggle: () -> Unit, onDelete: () -> Unit) {
    val timeFormatted = String.format(Locale.getDefault(), "%02d:%02d", alarm.hour, alarm.minute)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(if (alarm.isActive) NeonCyan.copy(alpha = 0.6f) else CyberCardBorder)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = timeFormatted,
                    color = if (alarm.isActive) TextPrimary else TextMuted,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = alarm.label,
                    color = if (alarm.isActive) NeonCyan else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = alarm.isActive,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyberBg,
                        checkedTrackColor = NeonCyan,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = CyberCardBg
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Alarm",
                        tint = Color(0xFFFF5252)
                    )
                }
            }
        }
    }
}

@Composable
fun AddAlarmDialog(onDismiss: () -> Unit, onAdd: (Int, Int, String) -> Unit) {
    var hourStr by remember { mutableStateOf("07") }
    var minStr by remember { mutableStateOf("30") }
    var labelStr by remember { mutableStateOf("Wake Up") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberCardBg,
        title = {
            Text("SET JARVIS ALARM", color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = hourStr,
                        onValueChange = { hourStr = it },
                        label = { Text("Hour (0-23)") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = CyberCardBorder)
                    )

                    OutlinedTextField(
                        value = minStr,
                        onValueChange = { minStr = it },
                        label = { Text("Minute (0-59)") },
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = CyberCardBorder)
                    )
                }

                OutlinedTextField(
                    value = labelStr,
                    onValueChange = { labelStr = it },
                    label = { Text("Alarm Label / Task") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = CyberCardBorder),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hourStr.toIntOrNull()?.coerceIn(0, 23) ?: 7
                    val m = minStr.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    onAdd(h, m, labelStr)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("SET ALARM", color = CyberBg, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}
