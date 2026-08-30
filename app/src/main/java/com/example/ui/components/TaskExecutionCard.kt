package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.StepStatus
import com.example.engine.TaskPlan
import com.example.ui.theme.*

@Composable
fun TaskExecutionCard(
    plan: TaskPlan?,
    modifier: Modifier = Modifier
) {
    if (plan == null || plan.steps.isEmpty()) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("task_execution_card"),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(NeonCyan.copy(alpha = 0.5f))),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = NeonCyan,
                        modifier = Modifier.size(8.dp)
                    ) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TASK EXECUTION PIPELINE",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "${plan.steps.count { it.status == StepStatus.SUCCESS }}/${plan.steps.size} DONE",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plan.steps.forEachIndexed { idx, step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = when (step.status) {
                                    StepStatus.RUNNING -> NeonCyan.copy(alpha = 0.12f)
                                    StepStatus.SUCCESS -> StatusOnline.copy(alpha = 0.10f)
                                    StepStatus.FAILED -> StatusOffline.copy(alpha = 0.15f)
                                    StepStatus.RETRYING -> StatusWarning.copy(alpha = 0.15f)
                                    StepStatus.PENDING, StepStatus.WAITING_CONFIRMATION -> Color(0xFF0F172A)
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = when (step.status) {
                                    StepStatus.RUNNING -> NeonCyan
                                    StepStatus.SUCCESS -> StatusOnline.copy(alpha = 0.4f)
                                    StepStatus.FAILED -> StatusOffline
                                    StepStatus.RETRYING -> StatusWarning
                                    else -> CyberCardBorder
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Step status indicator icon
                        when (step.status) {
                            StepStatus.RUNNING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = NeonCyan,
                                    strokeWidth = 2.dp
                                )
                            }
                            StepStatus.SUCCESS -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Success",
                                    tint = StatusOnline,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            StepStatus.RETRYING -> {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retrying",
                                    tint = StatusWarning,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            StepStatus.FAILED -> {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "Failed",
                                    tint = StatusOffline,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            StepStatus.PENDING, StepStatus.WAITING_CONFIRMATION -> {
                                Text(
                                    text = "${idx + 1}",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = step.descriptionHinglish,
                                color = if (step.status == StepStatus.RUNNING) Color.White else TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (step.status == StepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal
                            )
                            if (step.status == StepStatus.RETRYING) {
                                Text(
                                    text = "Auto-retrying step with verify-check...",
                                    color = StatusWarning,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
