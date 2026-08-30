package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.TaskPlan
import com.example.ui.theme.*

@Composable
fun RiskyActionConfirmationDialog(
    plan: TaskPlan?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    if (plan == null) return

    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Confirmation Required",
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Confirmation Required",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = plan.confirmationPrompt.ifBlank { "Yeh ek sensitive action hai (jaise payment ya deletion). Kya aap confirm karna chahte hain?" },
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Intent: ${plan.originalQuery}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("confirm_risky_action_btn")
            ) {
                Text("Confirm & Execute", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("cancel_risky_action_btn")
            ) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = CyberCardBg,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
    )
}
