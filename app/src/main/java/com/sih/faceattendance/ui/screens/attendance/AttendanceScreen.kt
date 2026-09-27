package com.sih.faceattendance.ui.screens.attendance

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sih.faceattendance.AttendanceApplication
import com.sih.faceattendance.core.*
import com.sih.faceattendance.data.local.entities.SessionEntity
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

    var latestLiveFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isScanInProgress by remember { mutableStateOf(false) }
    var isFaceInReticle by remember { mutableStateOf(false) }
    var isCheckingFaceLive by remember { mutableStateOf(false) }

    // Real connected pipeline progress states (populated directly by ML models as they execute)
    val livePipelineSteps = remember { mutableStateListOf<PipelineStepProgress>() }
    var telemetryResult by remember { mutableStateOf<PipelineTelemetry?>(null) }
    var showDevTools by remember { mutableStateOf(false) }

    // Fallback default session if none chosen
    val session = activeSession ?: remember {
        SessionEntity(
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

    // Function to run the actual connected pipeline
    fun runConnectedScan(targetFrame: Bitmap) {
        if (isScanInProgress) return
        isScanInProgress = true
        livePipelineSteps.clear()
        telemetryResult = null

        scope.launch {
            val result = app.pipelineCoordinator.processFrame(
                frameBitmap = targetFrame,
                activeSession = session,
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
                            text = "${session.title} (${session.batchCode})",
                            fontSize = 12.sp,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                actions = {
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
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
                                    ReportDetailRow("Training Session:", "${session.title} (${session.batchCode})")
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
                                            text = "Extracted 128-Dim MobileFaceNet Vector Preview:",
                                            fontSize = 10.sp,
                                            color = TextSecondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        val snippet = telemetry.sampleEmbeddingSnippet?.joinToString(", ") { String.format("%.3f", it) }
                                            ?: matchedStudent.faceEmbedding.take(6).joinToString(", ") { String.format("%.3f", it) }
                                        Text(
                                            text = "[$snippet, ... +122 dimensions]",
                                            fontSize = 11.sp,
                                            color = PrimaryBlue,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            } else {
                                // Rejection Reason Card
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "REASON FOR REJECTION:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CrimsonAlert
                                    )
                                    Text(
                                        text = telemetry.failureReason ?: telemetry.statusMessage,
                                        fontSize = 14.sp,
                                        color = TextPrimary
                                    )

                                    if (telemetry.liveFaceCrop != null) {
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
                                                                    isFaceInReticle = faces.isNotEmpty()
                                                                }
                                                            } catch (_: Exception) {
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
                                    .size(240.dp)
                                    .border(
                                        width = if (isFaceInReticle) 2.5.dp else 2.dp,
                                        color = when {
                                            isScanInProgress -> PrimaryBlue
                                            isFaceInReticle -> EmeraldVerified
                                            else -> PrimaryBlue.copy(alpha = 0.6f)
                                        },
                                        shape = RoundedCornerShape(24.dp)
                                    )
                            ) {
                                Surface(
                                    color = if (isFaceInReticle) EmeraldVerified.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.55f),
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
                                            imageVector = if (isFaceInReticle) Icons.Default.CheckCircle else Icons.Default.Face,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = if (isFaceInReticle) "HUMAN FACE DETECTED" else "ALIGN FACE IN RETICLE",
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

                    // BOTTOM INTENTIONAL SCAN BUTTON
                    Surface(
                        color = LightSurface,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val frame = latestLiveFrame ?: return@Button
                                    runConnectedScan(frame)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !isScanInProgress && latestLiveFrame != null
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = if (isScanInProgress) "VERIFYING BIOMETRICS..." else "SCAN ATTENDANCE",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Status: Point at student & tap to verify",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = if (isOnline) "Server Online" else "100% Offline Mode",
                                    fontSize = 11.sp,
                                    color = if (isOnline) PrimaryBlue else AmberOffline,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
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
