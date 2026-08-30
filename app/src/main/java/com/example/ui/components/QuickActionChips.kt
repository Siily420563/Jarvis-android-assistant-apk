package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyberCardBg
import com.example.ui.theme.CyberCardBorder
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.TextPrimary

@Composable
fun QuickActionChips(
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = listOf(
        "Mom ko WhatsApp pe bolo aa raha hoon",
        "Alarm lagao kal subah 7:00 AM",
        "Girlfriend mode on karo",
        "Open YouTube",
        "Scroll down",
        "Yaad rakhna mera passport drawer me hai",
        "Time kya hua hai?"
    )

    LazyRow(
        modifier = modifier.testTag("quick_action_chips_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(presets) { preset ->
            FilterChip(
                selected = false,
                onClick = { onChipClick(preset) },
                label = {
                    Text(
                        text = preset,
                        fontSize = 12.sp,
                        color = TextPrimary
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = CyberCardBg,
                    labelColor = TextPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = CyberCardBorder,
                    selectedBorderColor = NeonCyan
                ),
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}
