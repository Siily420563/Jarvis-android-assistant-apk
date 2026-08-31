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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.persona.PersonaType
import com.example.ui.MainViewModel
import com.example.ui.screens.AlarmsScreen
import com.example.ui.screens.BrainScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MacrosScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            MyApplicationTheme {
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    viewModel.checkSystemPermissionsStatus()
                    if (isGranted) {
                        viewModel.startListening()
                    }
                }

                MainScreenContent(
                    viewModel = viewModel,
                    onRequestMicPermission = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
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

enum class SaraTab {
    HOME, MACROS, MEMORY, ALARMS, STUDIO
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    viewModel: MainViewModel,
    onRequestMicPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(SaraTab.HOME) }
    val hasAnyKey by viewModel.hasAnyKey.collectAsState()
    val activePersona by viewModel.activePersona.collectAsState()

    val personaAccent = when (activePersona) {
        PersonaType.GIRLFRIEND -> SaraPink
        PersonaType.PROFESSIONAL -> NeonCyan
        PersonaType.BOLD -> Color(0xFFF97316)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = personaAccent,
                            modifier = Modifier.size(10.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "SARA NEURAL LINK",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.5.sp
                            )
                            Text(
                                text = "Mode: ${activePersona.displayName}",
                                fontSize = 11.sp,
                                color = personaAccent
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        color = if (hasAnyKey) StatusOnline.copy(alpha = 0.2f) else Color(0xFFF59E0B).copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(
                            text = if (hasAnyKey) "ONLINE" else "LOCAL AI",
                            color = if (hasAnyKey) StatusOnline else Color(0xFFF59E0B),
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
                contentColor = personaAccent,
                modifier = Modifier.testTag("sara_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = selectedTab == SaraTab.HOME,
                    onClick = { selectedTab = SaraTab.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home HUD") },
                    label = { Text("HUD", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = personaAccent,
                        indicatorColor = personaAccent.copy(alpha = 0.4f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == SaraTab.MACROS,
                    onClick = { selectedTab = SaraTab.MACROS },
                    icon = { Icon(Icons.Default.FlashOn, contentDescription = "Macros") },
                    label = { Text("Macros", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = personaAccent,
                        indicatorColor = personaAccent.copy(alpha = 0.4f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == SaraTab.MEMORY,
                    onClick = { selectedTab = SaraTab.MEMORY },
                    icon = { Icon(Icons.Default.Psychology, contentDescription = "Memory") },
                    label = { Text("Memory", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = personaAccent,
                        indicatorColor = personaAccent.copy(alpha = 0.4f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == SaraTab.ALARMS,
                    onClick = { selectedTab = SaraTab.ALARMS },
                    icon = { Icon(Icons.Default.AccessTime, contentDescription = "Alarms") },
                    label = { Text("Alarms", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = personaAccent,
                        indicatorColor = personaAccent.copy(alpha = 0.4f),
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == SaraTab.STUDIO,
                    onClick = { selectedTab = SaraTab.STUDIO },
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "Persona Studio") },
                    label = { Text("Studio", fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = personaAccent,
                        indicatorColor = personaAccent.copy(alpha = 0.4f),
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
                SaraTab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onRequestMicPermission = onRequestMicPermission,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onNavigateToBrain = { selectedTab = SaraTab.STUDIO }
                )
                SaraTab.MACROS -> MacrosScreen(
                    viewModel = viewModel,
                    onNavigateToHome = { selectedTab = SaraTab.HOME }
                )
                SaraTab.MEMORY -> MemoryScreen(viewModel = viewModel)
                SaraTab.ALARMS -> AlarmsScreen(viewModel = viewModel)
                SaraTab.STUDIO -> BrainScreen(viewModel = viewModel)
            }
        }
    }
}
