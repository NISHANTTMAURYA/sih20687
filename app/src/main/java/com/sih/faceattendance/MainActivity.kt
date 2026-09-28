package com.sih.faceattendance

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sih.faceattendance.core.*
import com.sih.faceattendance.data.local.entities.SessionEntity
import com.sih.faceattendance.ui.screens.attendance.AttendanceScreen
import com.sih.faceattendance.ui.screens.enrollment.EnrollmentScreen
import com.sih.faceattendance.ui.screens.queue.OfflineQueueScreen
import com.sih.faceattendance.ui.screens.sessions.SessionsScreen
import com.sih.faceattendance.ui.screens.students.StudentsScreen

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration

enum class AppTab(val title: String, val icon: ImageVector) {
    ENROLLMENT("1. Enrol", Icons.Default.PersonAdd),
    SESSIONS("2. Sessions", Icons.Default.Schedule),
    ATTENDANCE("3. Scan", Icons.Default.CameraAlt),
    QUEUE("4. Queue", Icons.Default.CloudSync),
    DATABASE("5. Database", Icons.Default.Storage)
}

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions handled gracefully by components
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request runtime Camera & Location permissions
        requestPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )

        val app = application as AttendanceApplication

        setContent {
            OfflineFaceAttendanceTheme {
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                var currentTab by remember { mutableStateOf(AppTab.ENROLLMENT) }
                var selectedSession by remember { mutableStateOf<SessionEntity?>(null) }
                val pendingCount by app.attendanceRepository.pendingCountFlow.collectAsState(initial = 0)
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    app.autoSyncEvent.collect { msg ->
                        snackbarHostState.showSnackbar(message = msg, duration = SnackbarDuration.Short)
                    }
                }

                @Composable
                fun ScreenContent(modifier: Modifier = Modifier) {
                    Surface(
                        modifier = modifier.fillMaxSize(),
                        color = DarkSlateBackground
                    ) {
                        when (currentTab) {
                            AppTab.ENROLLMENT -> {
                                EnrollmentScreen(
                                    onEnrollmentComplete = { currentTab = AppTab.DATABASE }
                                )
                            }
                            AppTab.SESSIONS -> {
                                SessionsScreen(
                                    currentSelectedSessionId = selectedSession?.sessionId ?: "DL-01",
                                    onSessionSelected = { session ->
                                        selectedSession = session
                                    },
                                    onStartAttendance = {
                                        currentTab = AppTab.ATTENDANCE
                                    }
                                )
                            }
                            AppTab.ATTENDANCE -> {
                                AttendanceScreen(
                                    activeSession = selectedSession,
                                    onNavigateToSessions = { currentTab = AppTab.SESSIONS }
                                )
                            }
                            AppTab.QUEUE -> {
                                OfflineQueueScreen()
                            }
                            AppTab.DATABASE -> {
                                StudentsScreen(
                                    onNavigateToEnrollment = { currentTab = AppTab.ENROLLMENT }
                                )
                            }
                        }
                    }
                }

                if (isLandscape) {
                    // TABLET & HORIZONTAL LANDSCAPE LAYOUT: Left Navigation Rail + Content
                    Row(modifier = Modifier.fillMaxSize()) {
                        NavigationRail(
                            containerColor = LightSurface,
                            header = {
                                Surface(
                                    color = PrimaryBlueContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                ) {
                                    Text(
                                        text = "NCCT",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = PrimaryBlue,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        ) {
                            AppTab.values().forEach { tab ->
                                val isSelected = currentTab == tab
                                NavigationRailItem(
                                    selected = isSelected,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        BadgedBox(
                                            badge = {
                                                if (tab == AppTab.QUEUE && pendingCount > 0) {
                                                    Badge(containerColor = AmberOffline) {
                                                        Text("$pendingCount", color = Color.White, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.title
                                            )
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = tab.title.substringAfter(". "),
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = PrimaryBlue,
                                        selectedTextColor = PrimaryBlue,
                                        indicatorColor = PrimaryBlueContainer,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )
                            }
                        }

                        Scaffold(
                            modifier = Modifier.weight(1f),
                            containerColor = DarkSlateBackground,
                            snackbarHost = { SnackbarHost(snackbarHostState) }
                        ) { innerPadding ->
                            ScreenContent(modifier = Modifier.padding(innerPadding))
                        }
                    }
                } else {
                    // VERTICAL PORTRAIT LAYOUT: Content + Bottom Navigation Bar
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = DarkSlateBackground,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        bottomBar = {
                            NavigationBar(
                                containerColor = LightSurface,
                                tonalElevation = 2.dp
                            ) {
                                AppTab.values().forEach { tab ->
                                    val isSelected = currentTab == tab
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = { currentTab = tab },
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (tab == AppTab.QUEUE && pendingCount > 0) {
                                                        Badge(containerColor = AmberOffline) {
                                                            Text("$pendingCount", color = Color.White, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = tab.icon,
                                                    contentDescription = tab.title
                                                )
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = tab.title,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = PrimaryBlue,
                                            selectedTextColor = PrimaryBlue,
                                            indicatorColor = PrimaryBlueContainer,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        ScreenContent(modifier = Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
