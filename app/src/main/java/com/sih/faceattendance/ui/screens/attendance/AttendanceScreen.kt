package com.sih.faceattendance.ui.screens.attendance

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.sih.faceattendance.AttendanceApplication
import com.sih.faceattendance.core.*
import com.sih.faceattendance.data.local.entities.AttendanceRecordEntity
import com.sih.faceattendance.data.local.entities.SessionEntity
import com.sih.faceattendance.data.local.entities.SyncStatus
import com.sih.faceattendance.ml.PipelineStage
import com.sih.faceattendance.ml.PipelineStepProgress
import com.sih.faceattendance.ml.PipelineTelemetry
import com.sih.faceattendance.ui.components.StudentAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    activeSession: SessionEntity?,
    onNavigateToSessions: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as AttendanceApplication
    val scope = rememberCoroutineScope()

    val isOnline by app.networkMonitor.isOnline.collectAsState()
    val currentLocation by app.locationHelper.currentLocation.collectAsState()
    val accuracyMeters by app.locationHelper.accuracyMeters.collectAsState()
    val isGpsActive by app.locationHelper.isGpsActive.collectAsState()

    var hasLocationPermission by remember { mutableStateOf(app.locationHelper.hasLocationPermission()) }
    var isLocationEnabled by remember { mutableStateOf(app.locationHelper.isLocationServiceEnabled()) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        isLocationEnabled = app.locationHelper.isLocationServiceEnabled()
        if (granted) {
            app.locationHelper.startLocationUpdates()
        }
    }

    DisposableEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            app.locationHelper.startLocationUpdates()
        }
        onDispose {
            app.locationHelper.stopLocationUpdates()
        }
    }

    var latestLiveFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isScanInProgress by remember { mutableStateOf(false) }
    var isFaceInReticle by remember { mutableStateOf(false) }
    var isCheckingFaceLive by remember { mutableStateOf(false) }
    // Live blink count tracked from passive frame loop — shown in reticle HUD
    var liveBlinks by remember { mutableIntStateOf(0) }

    // Real connected pipeline progress states (populated directly by ML models as they execute)
    val livePipelineSteps = remember { mutableStateListOf<PipelineStepProgress>() }
    var telemetryResult by remember { mutableStateOf<PipelineTelemetry?>(null) }
    var showDevTools by remember { mutableStateOf(false) }

    // Observe active sessions from database (automatically reflects server updates)
    val allSessionsFromDb by app.sessionRepository.activeSessionsFlow.collectAsState(initial = emptyList())
    val currentSession = remember(allSessionsFromDb, activeSession) {
        allSessionsFromDb.find { it.sessionId == (activeSession?.sessionId ?: "DL-01") }
            ?: activeSession
            ?: SessionEntity(
                sessionId = "DL-01",
                title = "Digital Literacy",
                batchCode = "DL-01",
                startTime = "09:00 AM",
                endTime = "11:00 AM",
                centerName = "NCCT Regional Training Center, Sector 5",
                centerLatitude = 19.0760,
                centerLongitude = 72.8777,
                allowedRadiusMeters = 100.0f
            )
    }

    var isSyncingLocations by remember { mutableStateOf(false) }
    var syncLocationNotice by remember { mutableStateOf<String?>(null) }

    // Auto-fetch latest session center locations from server whenever online connection is active
    LaunchedEffect(isOnline) {
        if (isOnline) {
            try {
                app.sessionRepository.syncSessionsFromServer(com.sih.faceattendance.data.remote.NetworkClient.apiService)
            } catch (_: Exception) {}
        }
    }

    // Live pre-check Haversine distance between phone GPS and server-assigned session center
    val liveDistanceMeters = remember(currentLocation, currentSession) {
        if (currentLocation.first == 0.0 && currentLocation.second == 0.0) {
            -1f
        } else {
            val lat1 = currentLocation.first
            val lon1 = currentLocation.second
            val lat2 = currentSession.centerLatitude
            val lon2 = currentSession.centerLongitude
            val earthRadius = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                    kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                    kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
            val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
            (earthRadius * c).toFloat()
        }
    }
    val isInsideGeofence = liveDistanceMeters in 0f..currentSession.allowedRadiusMeters

    // Live list of attendance records marked for this session today
    val markedRecords by app.attendanceRepository.getRecordsForSessionFlow(currentSession.sessionId)
        .collectAsState(initial = emptyList())
    var showMarkedAttendanceDialog by remember { mutableStateOf(false) }

    // Function to run the actual connected pipeline
    fun runConnectedScan(targetFrame: Bitmap) {
        if (isScanInProgress) return
        isScanInProgress = true
        livePipelineSteps.clear()
        telemetryResult = null

        scope.launch {
            val result = app.pipelineCoordinator.processFrame(
                frameBitmap = targetFrame,
                activeSession = currentSession,
                deviceLatitude = currentLocation.first,
                deviceLongitude = currentLocation.second,
                onProgress = { stepUpdate ->
                    scope.launch(Dispatchers.Main) {
                        val existingIndex = livePipelineSteps.indexOfFirst { it.stepIndex == stepUpdate.stepIndex }
                        if (existingIndex >= 0) {
                            livePipelineSteps[existingIndex] = stepUpdate
                        } else {
                            livePipelineSteps.add(stepUpdate)
                        }
                    }
                }
            )

            withContext(Dispatchers.Main) {
                telemetryResult = result
                isScanInProgress = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "3. Face Attendance Scanner",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${currentSession.title} (${currentSession.batchCode})",
                            fontSize = 12.sp,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                actions = {
                    // Marked Attendance Counter Badge
                    BadgedBox(
                        badge = {
                            if (markedRecords.isNotEmpty()) {
                                Badge(
                                    containerColor = EmeraldVerified,
                                    contentColor = Color.White
                                ) {
                                    Text("${markedRecords.size}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    ) {
                        IconButton(onClick = { showMarkedAttendanceDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.People,
                                contentDescription = "Marked Students Today",
                                tint = PrimaryBlue
                            )
                        }
                    }
                    IconButton(onClick = { showDevTools = !showDevTools }) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Security Tests",
                            tint = if (showDevTools) PrimaryBlue else TextSecondary
                        )
                    }
                    IconButton(onClick = onNavigateToSessions) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch Session",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LightSurface)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(LightBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // REAL-TIME GEOFENCE PRE-CHECK STATUS CARD (COMPACT & CLEAN)
                Surface(
                    color = when {
                        liveDistanceMeters < 0f -> LightSurface
                        isInsideGeofence -> EmeraldContainer
                        else -> CrimsonContainer
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.2.dp,
                        when {
                            liveDistanceMeters < 0f -> LightCardBorder
                            isInsideGeofence -> EmeraldVerified.copy(alpha = 0.6f)
                            else -> CrimsonAlert.copy(alpha = 0.6f)
                        }
                    ),
                    shadowElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = when {
                                        liveDistanceMeters < 0f -> Icons.Default.GpsNotFixed
                                        isInsideGeofence -> Icons.Default.CheckCircle
                                        else -> Icons.Default.GpsOff
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        liveDistanceMeters < 0f -> TextMuted
                                        isInsideGeofence -> EmeraldVerified
                                        else -> CrimsonAlert
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = when {
                                        !hasLocationPermission -> "GPS PERMISSION NEEDED"
                                        !isLocationEnabled -> "PHONE GPS TURNED OFF"
                                        liveDistanceMeters < 0f -> "ACQUIRING GPS..."
                                        isInsideGeofence -> "INSIDE GEOFENCE (${liveDistanceMeters.toInt()}m)"
                                        else -> "OUTSIDE GEOFENCE (${liveDistanceMeters.toInt()}m away)"
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = when {
                                        !hasLocationPermission || !isLocationEnabled -> CrimsonAlert
                                        liveDistanceMeters < 0f -> TextPrimary
                                        isInsideGeofence -> EmeraldVerified
                                        else -> CrimsonAlert
                                    }
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!hasLocationPermission) {
                                    Button(
                                        onClick = {
                                            locationPermissionLauncher.launch(
                                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                            )
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert)
                                    ) {
                                        Text("Allow GPS", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                } else if (!isLocationEnabled) {
                                    Button(
                                        onClick = {
                                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert)
                                    ) {
                                        Text("Turn ON GPS", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                } else if (currentLocation.first != 0.0) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                isSyncingLocations = true
                                                // 1. ALWAYS update local SQLite Room database FIRST (100% offline-first)
                                                app.sessionRepository.updateSessionLocation(
                                                    sessionId = currentSession.sessionId,
                                                    latitude = currentLocation.first,
                                                    longitude = currentLocation.second,
                                                    centerName = "${currentSession.centerName.split(" • ").firstOrNull() ?: currentSession.centerName} (Local GPS)"
                                                )
                                                
                                                // 2. If online, also notify central server in background
                                                if (isOnline) {
                                                    try {
                                                        val report = com.sih.faceattendance.data.remote.dto.PhoneLocationReportDto(
                                                            latitude = currentLocation.first,
                                                            longitude = currentLocation.second
                                                        )
                                                        val res = com.sih.faceattendance.data.remote.NetworkClient.apiService.reportPhoneLocation(report)
                                                        if (res.isSuccessful && res.body() != null) {
                                                            syncLocationNotice = "✓ Geofence set to this Phone (Local DB + Server Updated)"
                                                        } else {
                                                            syncLocationNotice = "✓ Geofence set locally in Phone DB"
                                                        }
                                                    } catch (_: Exception) {
                                                        syncLocationNotice = "✓ Geofence set locally in Phone DB (Offline)"
                                                    }
                                                } else {
                                                    syncLocationNotice = "✓ Geofence set locally in Phone DB (Offline Mode)"
                                                }
                                                isSyncingLocations = false
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldVerified),
                                        enabled = !isSyncingLocations
                                    ) {
                                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(11.dp), tint = Color.White)
                                        Spacer(Modifier.width(3.dp))
                                        Text("Set Here", fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                if (isOnline) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                isSyncingLocations = true
                                                val res = app.sessionRepository.syncSessionsFromServer(com.sih.faceattendance.data.remote.NetworkClient.apiService)
                                                isSyncingLocations = false
                                                syncLocationNotice = if (res.isSuccess) "✓ Downloaded latest coordinates from server!" else "Server unreachable"
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                        enabled = !isSyncingLocations
                                    ) {
                                        if (isSyncingLocations) {
                                            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                        } else {
                                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(11.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("Sync", fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Center: ${currentSession.centerName} (${currentSession.allowedRadiusMeters.toInt()}m)",
                                fontSize = 9.5.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (currentLocation.first != 0.0) "${String.format("%.4f", currentLocation.first)}, ${String.format("%.4f", currentLocation.second)} (±${accuracyMeters.toInt()}m)" else "🛰️ Acquiring GPS...",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isInsideGeofence) EmeraldVerified else if (currentLocation.first == 0.0) TextMuted else CrimsonAlert,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (syncLocationNotice != null) {
                            Text(
                                text = syncLocationNotice!!,
                                fontSize = 9.5.sp,
                                color = PrimaryBlue,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Developer Security Tests Panel (Dropdown)
                if (showDevTools) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = LightSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "SECURITY TEST SUITE (SIH REJECTION SIMULATORS)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue,
                                letterSpacing = 0.5.sp
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Simulate Spoof Attack (Photo/Print)", fontSize = 12.sp, color = TextPrimary)
                                Switch(
                                    checked = app.pipelineCoordinator.simulateSpoofAttack,
                                    onCheckedChange = { app.pipelineCoordinator.simulateSpoofAttack = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Simulate Phone Screen Present", fontSize = 12.sp, color = TextPrimary)
                                Switch(
                                    checked = app.pipelineCoordinator.simulatePhonePresent,
                                    onCheckedChange = { app.pipelineCoordinator.simulatePhonePresent = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Simulate Outside Campus Geofence", fontSize = 12.sp, color = TextPrimary)
                                Switch(
                                    checked = app.pipelineCoordinator.simulateLocationInvalid,
                                    onCheckedChange = { app.pipelineCoordinator.simulateLocationInvalid = it }
                                )
                            }

                            // Test Real Enrolled Student Photo (Nishant Maurya NCCT1001.jpg)
                            Button(
                                onClick = {
                                    try {
                                        val stream = context.assets.open("students/NCCT1001.jpg")
                                        val realStudentBitmap = BitmapFactory.decodeStream(stream)
                                        stream.close()
                                        if (realStudentBitmap != null) {
                                            runConnectedScan(realStudentBitmap)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("AttendanceTest", "Failed loading real student photo", e)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                enabled = !isScanInProgress
                            ) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Test Scan Real Student Photo (Nishant)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // If scan has produced a result (Match or Rejection), display the detailed verification report card!
                if (telemetryResult != null && !isScanInProgress) {
                    val telemetry = telemetryResult!!
                    val isMatch = telemetry.stage == PipelineStage.SUCCESS
                    val matchedStudent = telemetry.matchedStudent

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = LightSurface),
                        border = androidx.compose.foundation.BorderStroke(
                            1.5.dp,
                            if (isMatch) EmeraldVerified else CrimsonAlert
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Header Status
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMatch) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        contentDescription = null,
                                        tint = if (isMatch) EmeraldVerified else CrimsonAlert,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Text(
                                        text = if (isMatch) "ATTENDANCE VERIFIED" else "VERIFICATION REJECTED",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = if (isMatch) EmeraldVerified else CrimsonAlert
                                    )
                                }

                                Surface(
                                    color = if (isMatch) EmeraldContainer else CrimsonContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = if (isMatch) "PRESENT" else "REJECTED",
                                        color = if (isMatch) EmeraldVerified else CrimsonAlert,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = LightCardBorder)

                            // SIDE-BY-SIDE FACE COMPARISON (LIVE CAMERA vs DATABASE STORED PHOTO)
                            if (isMatch && matchedStudent != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "BIOMETRIC PHOTO VERIFICATION (LIVE vs DATABASE)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary,
                                        letterSpacing = 0.5.sp
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Left: Live Camera Capture
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(100.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(LightSubtle)
                                                    .border(1.5.dp, PrimaryBlue, RoundedCornerShape(12.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (telemetry.liveFaceCrop != null) {
                                                    Image(
                                                        bitmap = telemetry.liveFaceCrop.asImageBitmap(),
                                                        contentDescription = "Live Face Capture",
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                } else {
                                                    Icon(Icons.Default.Face, contentDescription = null, tint = TextMuted)
                                                }
                                            }
                                            Text("Live Camera", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        }

                                        // Center: Match Confidence Pill
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = EmeraldVerified)
                                            Surface(
                                                color = EmeraldContainer,
                                                shape = RoundedCornerShape(8.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldVerified.copy(alpha = 0.4f))
                                            ) {
                                                Text(
                                                    text = "${(telemetry.similarityScore * 100).toInt()}% Match",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = EmeraldVerified,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                            Text("Cosine: ${String.format("%.3f", telemetry.similarityScore)}", fontSize = 10.sp, color = TextMuted)
                                        }

                                        // Right: Database Stored Photo
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            StudentAvatar(
                                                photoUri = matchedStudent.photoUri,
                                                size = 100.dp,
                                                shapeCorner = 12.dp
                                            )
                                            Text("Database Record", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        }
                                    }
                                }

                                HorizontalDivider(color = LightCardBorder)

                                // Student & Session Details
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ReportDetailRow("Student Name:", matchedStudent.name)
                                    ReportDetailRow("Student ID:", matchedStudent.studentId)
                                    ReportDetailRow("Roll Number:", matchedStudent.rollNumber)
                                    ReportDetailRow("Enrolled Course:", matchedStudent.course)
                                    ReportDetailRow("Training Session:", "${currentSession.title} (${currentSession.batchCode})")
                                    ReportDetailRow("Timestamp:", SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date()))
                                    ReportDetailRow("Liveness Test:", "PASSED (MiniFASNetV2: ${String.format("%.2f", telemetry.livenessScore)})")
                                    ReportDetailRow("Campus Geofence:", "VALID (${telemetry.distanceMeters.toInt()}m from Center)")
                                    ReportDetailRow("Offline Storage:", "Room SQLite (Pending Central Sync)")
                                }

                                // 128-Dim Embedding Snippet
                                Surface(
                                    color = LightSubtle,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Extracted MobileFaceNet Vector Preview:",
                                            fontSize = 10.sp,
                                            color = TextSecondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val emb = telemetry.sampleEmbeddingSnippet ?: matchedStudent.faceEmbedding.toList()
                                        val snippet = emb.take(6).joinToString(", ") { String.format("%.3f", it) }
                                        Text(
                                            text = "[$snippet, ... +${emb.size - 6} dimensions]",
                                            fontSize = 11.sp,
                                            color = PrimaryBlue,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            } else {
                                // Rejection Reason Card with Visual Threat Inspection Overlay
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = "REASON FOR REJECTION:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CrimsonAlert
                                    )
                                    Text(
                                        text = telemetry.failureReason ?: telemetry.statusMessage,
                                        fontSize = 13.sp,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    // VISUAL SECURITY INSPECTION OVERLAY (AI THREAT DETECTED)
                                    if (telemetry.inspectionOverlayBitmap != null) {
                                        Surface(
                                            color = CrimsonContainer,
                                            shape = RoundedCornerShape(12.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.5.dp, CrimsonAlert)
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(Icons.Default.Security, contentDescription = null, tint = CrimsonAlert, modifier = Modifier.size(18.dp))
                                                    Text(
                                                        text = "VISUAL SECURITY INSPECTION (AI BOUNDING BOXES)",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = CrimsonAlert
                                                    )
                                                }

                                                Image(
                                                    bitmap = telemetry.inspectionOverlayBitmap!!.asImageBitmap(),
                                                    contentDescription = "Threat Inspection Frame",
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(210.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .border(1.dp, CrimsonAlert, RoundedCornerShape(8.dp)),
                                                    contentScale = ContentScale.Fit
                                                )

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "🟩 Green: Live Face Region",
                                                        fontSize = 10.sp,
                                                        color = EmeraldVerified,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "🟥 Red: Detected Replay Screen",
                                                        fontSize = 10.sp,
                                                        color = CrimsonAlert,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    } else if (telemetry.liveFaceCrop != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(100.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(LightSubtle)
                                                .border(1.5.dp, CrimsonAlert, RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                bitmap = telemetry.liveFaceCrop.asImageBitmap(),
                                                contentDescription = "Captured Face",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                }
                            }

                            // Next Scan Action Button
                            Button(
                                onClick = {
                                    telemetryResult = null
                                    livePipelineSteps.clear()
                                    // Reset blink tracking for next attempt
                                    app.livenessEngine.resetBlinkHistory()
                                    liveBlinks = 0
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isMatch) EmeraldVerified else PrimaryBlue
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isMatch) "NEXT STUDENT / NEW SCAN" else "RETRY SCAN",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                } else {
                    // LIVE CAMERA PREVIEW & DISCRETE SCANNER CONTROL
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = LightSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            // CameraX Viewfinder
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx)
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                    cameraProviderFuture.addListener({
                                        try {
                                            val cameraProvider = cameraProviderFuture.get()
                                            val preview = Preview.Builder().build()
                                            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                                            preview.setSurfaceProvider(previewView.surfaceProvider)

                                            val imageAnalysis = ImageAnalysis.Builder()
                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                .build()

                                            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                                try {
                                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                                    val rawBitmap = imageProxy.toBitmap()
                                                    imageProxy.close()

                                                    val matrix = android.graphics.Matrix()
                                                    if (rotation != 0) {
                                                        matrix.postRotate(rotation.toFloat())
                                                    }
                                                    matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
                                                    val upright = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                                                    latestLiveFrame = upright

                                                     // Background live face check to inform the operator in real time
                                                    if (!isScanInProgress && !isCheckingFaceLive) {
                                                        isCheckingFaceLive = true
                                                        scope.launch(Dispatchers.Default) {
                                                            try {
                                                                val faces = app.faceDetectorEngine.detectFaces(upright, 0)
                                                                withContext(Dispatchers.Main) {
                                                                    if (faces.isEmpty()) {
                                                                        isFaceInReticle = false
                                                                    } else {
                                                                        val primaryFace = faces[0]
                                                                        val box = primaryFace.boundingBox
                                                                        val W = upright.width.toFloat()
                                                                        val H = upright.height.toFloat()
                                                                        val faceWidthRatio = box.width().toFloat() / W
                                                                        val faceHeightRatio = box.height().toFloat() / H
                                                                        val dx = kotlin.math.abs(box.centerX().toFloat() - W / 2f) / W
                                                                        val dy = kotlin.math.abs(box.centerY().toFloat() - H / 2f) / H

                                                                        // Real human face: Centered in reticle AND substantial size (not tiny ceiling/curtain artifact)
                                                                        val isCentered = dx <= 0.25f && dy <= 0.28f
                                                                        val isSubstantialSize = faceWidthRatio >= 0.18f && faceHeightRatio >= 0.18f
                                                                        isFaceInReticle = isCentered && isSubstantialSize

                                                                        // Feed eye-open probabilities into liveness engine for blink tracking.
                                                                        // This runs every frame so the engine builds up a temporal blink history.
                                                                        // Static photos / video replays will NOT produce genuine blink events.
                                                                        app.livenessEngine.recordEyeState(
                                                                            leftEyeOpenProb = primaryFace.leftEyeOpenProbability,
                                                                            rightEyeOpenProb = primaryFace.rightEyeOpenProbability
                                                                        )
                                                                        liveBlinks = app.livenessEngine.getBlinkCount()
                                                                    }
                                                                }
                                                            } catch (_: Exception) {
                                                                withContext(Dispatchers.Main) { isFaceInReticle = false }
                                                            } finally {
                                                                isCheckingFaceLive = false
                                                            }
                                                        }
                                                    }
                                                } catch (_: Exception) {
                                                    imageProxy.close()
                                                    isCheckingFaceLive = false
                                                }
                                            }

                                            cameraProvider.unbindAll()
                                            cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                cameraSelector,
                                                preview,
                                                imageAnalysis
                                            )
                                        } catch (e: Exception) {
                                            Log.e("AttendanceCamera", "Error binding camera", e)
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))

                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Reticle with Dynamic Green Glow on Face Detected
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.68f)
                                    .aspectRatio(0.82f)
                                    .border(
                                        width = if (isFaceInReticle) 2.5.dp else 2.dp,
                                        color = when {
                                            isScanInProgress -> PrimaryBlue
                                            isFaceInReticle -> EmeraldVerified
                                            else -> PrimaryBlue.copy(alpha = 0.6f)
                                        },
                                        shape = RoundedCornerShape(22.dp)
                                    )
                            ) {
                                // Top label: face / blink status
                                Surface(
                                    color = when {
                                        isFaceInReticle && liveBlinks >= 1 -> EmeraldVerified.copy(alpha = 0.92f)
                                        isFaceInReticle -> Color(0xFFE68A00).copy(alpha = 0.92f) // amber — face found, no blink yet
                                        else -> Color.Black.copy(alpha = 0.55f)
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                isFaceInReticle && liveBlinks >= 1 -> Icons.Default.CheckCircle
                                                isFaceInReticle -> Icons.Default.Visibility
                                                else -> Icons.Default.Face
                                            },
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = when {
                                                isFaceInReticle && liveBlinks >= 1 -> "LIVE FACE · BLINKS: $liveBlinks ✓"
                                                isFaceInReticle -> "BLINK NATURALLY → THEN SCAN (Blinks: $liveBlinks)"
                                                else -> "ALIGN FACE IN RETICLE"
                                            },
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }

                            // Connected Real-Time Pipeline Progress Overlay
                            if (isScanInProgress || livePipelineSteps.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth(0.92f)
                                        .align(Alignment.Center),
                                    color = LightSurface.copy(alpha = 0.97f),
                                    shape = RoundedCornerShape(16.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.5.dp, PrimaryBlue),
                                    shadowElevation = 8.dp
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (isScanInProgress) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp, color = PrimaryBlue)
                                            } else {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldVerified)
                                            }
                                            Text(
                                                text = if (isScanInProgress) "RUNNING ON-DEVICE VERIFICATION..." else "PIPELINE COMPLETE",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (isScanInProgress) PrimaryBlue else EmeraldVerified
                                            )
                                        }

                                        HorizontalDivider(color = LightCardBorder)

                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            livePipelineSteps.forEach { step ->
                                                RealStepProgressRow(step)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // COMPACT UNIFIED SCANNER & ATTENDANCE STATUS CARD
                    Surface(
                        color = LightSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val frame = latestLiveFrame ?: return@Button
                                    runConnectedScan(frame)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isScanInProgress && latestLiveFrame != null
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isScanInProgress) "VERIFYING BIOMETRICS..." else "SCAN ATTENDANCE",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { showMarkedAttendanceDialog = true }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.People,
                                        contentDescription = null,
                                        tint = if (markedRecords.isNotEmpty()) EmeraldVerified else PrimaryBlue,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "${markedRecords.size} Marked Present (Tap to View)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (markedRecords.isNotEmpty()) EmeraldVerified else TextPrimary
                                    )
                                }

                                Text(
                                    text = if (isOnline) "🟢 Server Online" else "🟠 Offline Mode",
                                    fontSize = 10.5.sp,
                                    color = if (isOnline) PrimaryBlue else AmberOffline,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        // MARKED ATTENDANCE DIALOG VIEWER
        if (showMarkedAttendanceDialog) {
            Dialog(
                onDismissRequest = { showMarkedAttendanceDialog = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.85f)
                        .padding(8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = LightSurface,
                    shadowElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Marked Attendance Today",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${currentSession.title} (${currentSession.batchCode}) • ${markedRecords.size} Present",
                                    fontSize = 12.sp,
                                    color = PrimaryBlue,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            IconButton(onClick = { showMarkedAttendanceDialog = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                            }
                        }

                        HorizontalDivider(color = LightCardBorder)

                        if (markedRecords.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.PersonOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                                    Text("No attendance marked yet for this session.", color = TextSecondary, fontSize = 13.sp)
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(markedRecords) { record ->
                                    MarkedStudentItemRow(record)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkedStudentItemRow(record: AttendanceRecordEntity) {
    val timeFormatted = remember(record.timestamp) {
        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(record.timestamp))
    }
    Surface(
        color = LightSubtle,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = SkyContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = record.studentName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "ID: ${record.studentId}  •  ${(record.similarityScore * 100).toInt()}% match",
                    fontSize = 11.sp,
                    color = PrimaryBlue,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Marked at $timeFormatted",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }

            Surface(
                color = when (record.syncStatus) {
                    SyncStatus.SYNCED -> EmeraldContainer
                    SyncStatus.FAILED -> CrimsonContainer
                    else -> AmberContainer
                },
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = when (record.syncStatus) {
                            SyncStatus.SYNCED -> Icons.Default.CloudDone
                            SyncStatus.FAILED -> Icons.Default.CloudOff
                            else -> Icons.Default.CloudQueue
                        },
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = when (record.syncStatus) {
                            SyncStatus.SYNCED -> EmeraldVerified
                            SyncStatus.FAILED -> CrimsonAlert
                            else -> AmberOffline
                        }
                    )
                    Text(
                        text = when (record.syncStatus) {
                            SyncStatus.SYNCED -> "Synced"
                            SyncStatus.FAILED -> "Failed"
                            else -> "Offline"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (record.syncStatus) {
                            SyncStatus.SYNCED -> EmeraldVerified
                            SyncStatus.FAILED -> CrimsonAlert
                            else -> AmberOffline
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RealStepProgressRow(step: PipelineStepProgress) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            step.isRunning -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PrimaryBlue)
            }
            step.isSuccess -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldVerified, modifier = Modifier.size(16.dp))
            }
            else -> {
                Icon(Icons.Default.Cancel, contentDescription = null, tint = CrimsonAlert, modifier = Modifier.size(16.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${step.stepIndex}. ${step.stepName}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    step.isRunning -> PrimaryBlue
                    step.isSuccess -> TextPrimary
                    else -> CrimsonAlert
                }
            )
            if (step.detailMessage.isNotBlank()) {
                Text(
                    text = step.detailMessage,
                    fontSize = 10.sp,
                    color = when {
                        step.isRunning -> TextSecondary
                        step.isSuccess -> EmeraldVerified
                        else -> CrimsonAlert
                    },
                    fontFamily = if (step.detailMessage.startsWith("Vector:")) FontFamily.Monospace else FontFamily.Default
                )
            }
        }
    }
}

@Composable
private fun ReportDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Text(text = value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
