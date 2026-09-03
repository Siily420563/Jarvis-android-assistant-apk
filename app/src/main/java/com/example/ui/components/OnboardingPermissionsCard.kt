package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.DeviceActionHelper

@Composable
fun OnboardingPermissionsCard(
    isAccessibilityOnline: Boolean,
    isOverlayAuthorized: Boolean,
    isMicGranted: Boolean,
    isBatteryExempted: Boolean,
    isContactsGranted: Boolean,
    onRequestMic: () -> Unit,
    onRequestContacts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }

    val grantedCount = listOf(
        isAccessibilityOnline,
        isOverlayAuthorized,
        isMicGranted,
        isBatteryExempted,
        isContactsGranted
    ).count { it }

    val allGranted = grantedCount == 5

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("onboarding_permissions_card"),
        colors = CardDefaults.cardColors(containerColor = CyberCardBg),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (allGranted) StatusOnline else NeonCyan
            )
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (allGranted) StatusOnline.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.2f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (allGranted) Icons.Default.VerifiedUser else Icons.Default.Security,
                            contentDescription = null,
                            tint = if (allGranted) StatusOnline else NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ASSISTANT READINESS: $grantedCount/5 PERMISSIONS",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (allGranted) "All hardware & automation permissions active" else "Tap to configure required Android permissions",
                            color = if (allGranted) StatusOnline else TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(onClick = { isExpanded = !isExpanded }) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Details",
                        tint = TextSecondary
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded || !allGranted) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = CyberCardBorder)

                    // 1. Accessibility Service
                    PermissionRowItem(
                        step = "1",
                        title = "Accessibility Automation (Eyes & Hands)",
                        description = "Enables Jarvis to inspect screen nodes and autonomously tap/scroll.",
                        isGranted = isAccessibilityOnline,
                        actionLabel = "AUTHORIZE",
                        onAction = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    )

                    // 2. Draw Over Other Apps
                    PermissionRowItem(
                        step = "2",
                        title = "Draw Over Other Apps (HUD & Overlay)",
                        description = "Displays the floating voice orb and element highlight boxes over any app.",
                        isGranted = isOverlayAuthorized,
                        actionLabel = "ENABLE",
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(intent)
                            }
                        }
                    )

                    // 3. Microphone
                    PermissionRowItem(
                        step = "3",
                        title = "Microphone (Voice Control)",
                        description = "Allows continuous and turn-based voice command recognition.",
                        isGranted = isMicGranted,
                        actionLabel = "GRANT",
                        onAction = onRequestMic
                    )

                    // 4. Battery Optimization
                    PermissionRowItem(
                        step = "4",
                        title = "Battery Exemption (Background Assistant)",
                        description = "Prevents Android OS from killing Jarvis in the background.",
                        isGranted = isBatteryExempted,
                        actionLabel = "EXEMPT",
                        onAction = { DeviceActionHelper.requestIgnoreBatteryOptimization(context) }
                    )

                    // 5. Contacts & Phone
                    PermissionRowItem(
                        step = "5",
                        title = "Contacts & Phone Calls",
                        description = "Enables finding contacts by name and making direct phone calls or SMS.",
                        isGranted = isContactsGranted,
                        actionLabel = "GRANT",
                        onAction = onRequestContacts
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRowItem(
    step: String,
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(
                        if (isGranted) StatusOnline.copy(alpha = 0.2f) else CyberCardBorder,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isGranted) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = StatusOnline, modifier = Modifier.size(14.dp))
                } else {
                    Text(text = step, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    color = if (isGranted) Color.White else TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        }

        if (!isGranted) {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberBg
                )
            }
        } else {
            Text(
                text = "ACTIVE",
                color = StatusOnline,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
