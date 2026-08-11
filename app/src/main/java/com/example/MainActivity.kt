package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.MainViewModel
import com.example.ui.screens.AlarmsScreen
import com.example.ui.screens.BrainScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                // Request Record Audio & Notification permissions
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { /* Permissions updated */ }

                LaunchedEffect(Unit) {
                    val permsToRequest = mutableListOf<String>()
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permsToRequest.add(Manifest.permission.RECORD_AUDIO)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (permsToRequest.isNotEmpty()) {
                        permissionLauncher.launch(permsToRequest.toTypedArray())
                    }
                }

                MainScreenContent(
                    viewModel = viewModel,
                    onOpenAccessibilitySettings = {
                        try {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    },
                    onOpenOverlaySettings = {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkSystemPermissionsStatus()
    }
}

enum class JarvisTab {
    HOME, MEMORY, ALARMS, BRAIN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    viewModel: MainViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(JarvisTab.HOME) }
    val hasAnyKey by viewModel.hasAnyKey.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = NeonCyan,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "J.A.R.V.I.S. NEURAL LINK",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.5.sp
                        )
                    }
                },
                actions = {
                    Surface(
                        color = if (hasAnyKey) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFFEF4444).copy(alpha = 0.2f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (hasAnyKey) "CORE ACTIVE" else "KEY NEEDED",
                            color = if (hasAnyKey) StatusOnline else StatusOffline,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CyberBg)
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CyberCardBg,
                contentColor = NeonCyan
            ) {
                NavigationBarItem(
                    selected = selectedTab == JarvisTab.HOME,
                    onClick = { selectedTab = JarvisTab.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("HUD", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberBg,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == JarvisTab.MEMORY,
                    onClick = { selectedTab = JarvisTab.MEMORY },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "Memory") },
                    label = { Text("Memory", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberBg,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == JarvisTab.ALARMS,
                    onClick = { selectedTab = JarvisTab.ALARMS },
                    icon = { Icon(Icons.Default.AccessTime, contentDescription = "Alarms") },
                    label = { Text("Alarms", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberBg,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == JarvisTab.BRAIN,
                    onClick = { selectedTab = JarvisTab.BRAIN },
                    icon = { Icon(Icons.Default.Memory, contentDescription = "Brain") },
                    label = { Text("Brain", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = CyberBg,
                        selectedTextColor = NeonCyan,
                        indicatorColor = NeonCyan,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )
            }
        },
        containerColor = CyberBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                JarvisTab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onNavigateToBrain = { selectedTab = JarvisTab.BRAIN }
                )
                JarvisTab.MEMORY -> MemoryScreen(viewModel = viewModel)
                JarvisTab.ALARMS -> AlarmsScreen(viewModel = viewModel)
                JarvisTab.BRAIN -> BrainScreen(viewModel = viewModel)
            }
        }
    }
}

