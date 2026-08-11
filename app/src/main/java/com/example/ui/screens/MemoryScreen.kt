package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.db.UserMemory
import com.example.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun MemoryScreen(viewModel: MainViewModel) {
    val userMemories by viewModel.userMemories.collectAsState(initial = emptyList())
    var selectedCategory by remember { mutableStateOf("ALL") }
    var showAddDialog by remember { mutableStateOf(false) }

    val categories = listOf("ALL", "Personal", "Preference", "Location", "Task", "General")

    val filteredMemories = remember(userMemories, selectedCategory) {
        if (selectedCategory == "ALL") userMemories
        else userMemories.filter { it.category.equals(selectedCategory, ignoreCase = true) }
    }

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
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "Neural Index",
                        tint = NeonCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "NEURAL MEMORY BANK",
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
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Memory")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Category Filter Row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NeonCyan,
                            selectedLabelColor = CyberBg,
                            containerColor = CyberCardBg,
                            labelColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredMemories.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No cataloged neural memories in this category, Sir.",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredMemories, key = { it.id }) { memory ->
                        MemoryItemCard(
                            memory = memory,
                            onDelete = { viewModel.deleteMemory(memory) }
                        )
                    }
                }
            }
        }

        if (showAddDialog) {
            AddMemoryDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { fact, category ->
                    viewModel.addManualMemory(fact, category)
                    showAddDialog = false
                }
            )
        }
    }
}

@Composable
fun MemoryItemCard(memory: UserMemory, onDelete: () -> Unit) {
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
                Surface(
                    color = NeonCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = memory.category.uppercase(),
                        color = NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = memory.fact,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Memory",
                    tint = Color(0xFFFF5252)
                )
            }
        }
    }
}

@Composable
fun AddMemoryDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var factText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Location") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CyberCardBg,
        title = {
            Text("LOG NEURAL MEMORY", color = NeonCyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = factText,
                    onValueChange = { factText = it },
                    label = { Text("Memory Fact (e.g. Keys in bedroom drawer)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CyberCardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Category:", color = TextSecondary, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("Personal", "Preference", "Location", "Task", "General")) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (factText.isNotBlank()) onAdd(factText, selectedCategory) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("LOG FACT", color = CyberBg, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TextSecondary)
            }
        }
    )
}
