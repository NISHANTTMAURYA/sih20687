package com.sih.faceattendance.ui.screens.enrollment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.sih.faceattendance.data.local.entities.StudentEntity
import com.sih.faceattendance.ml.DetectedFaceResult
import com.sih.faceattendance.ui.components.StudentAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentScreen(
    onEnrollmentComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as AttendanceApplication
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var studentId by remember { mutableStateOf("") }
    var rollNumber by remember { mutableStateOf("") }
    var course by remember { mutableStateOf("Digital Literacy") }
    var selectedSessionId by remember { mutableStateOf("DL-01") }

    var latestFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentFaceDetected by remember { mutableStateOf<DetectedFaceResult?>(null) }

    var capturedFrontFaceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var enrolledStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var isProcessingAngle by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Multi-angle biometric registration states (0 = idle, 1 = Center, 2 = Left, 3 = Right, 4 = Complete)
    var registrationStep by remember { mutableStateOf(0) }
    val capturedVectors = remember { mutableStateListOf<FloatArray>() }
    var guidanceMessage by remember { mutableStateOf("Enter student details and tap 'Start Enrollment'") }

    // Smooth progress ring animated value
    val animatedProgress by animateFloatAsState(
        targetValue = when (registrationStep) {
            1 -> 0.33f
            2 -> 0.66f
            3, 4 -> 1.0f
            else -> 0.0f
        },
        animationSpec = tween(durationMillis = 500),
        label = "biometric_progress"
    )

    // Function to perform real angle capture from current camera frame
    fun captureCurrentAngle(step: Int) {
        if (isProcessingAngle) return
        val frame = latestFrameBitmap ?: return

        isProcessingAngle = true
        scope.launch {
            val faces = app.faceDetectorEngine.detectFaces(frame)
            if (faces.isEmpty()) {
                errorMessage = "No face in circle. Please look at the camera."
                isProcessingAngle = false
                return@launch
            }

            val face = faces[0]
            val box = face.boundingBox
            val marginX = (box.width() * 0.25f).toInt()
            val marginY = (box.height() * 0.25f).toInt()
            val safeLeft = (box.left - marginX).coerceIn(0, frame.width - 1)
            val safeTop = (box.top - marginY).coerceIn(0, frame.height - 1)
            val safeRight = (box.right + marginX).coerceIn(safeLeft + 1, frame.width)
            val safeBottom = (box.bottom + marginY).coerceIn(safeTop + 1, frame.height)

            val faceCrop = Bitmap.createBitmap(
                frame,
                safeLeft,
                safeTop,
                safeRight - safeLeft,
                safeBottom - safeTop
            )

            // Extract real MobileFaceNet 128-dim ArcFace embedding
            val vector = app.faceEmbeddingEngine.extractEmbedding(faceCrop)
            capturedVectors.add(vector)

            when (step) {
                1 -> {
                    capturedFrontFaceBitmap = faceCrop
                    registrationStep = 2
                    guidanceMessage = "Angle 1/3 (Center) captured ✓ Now tilt head slightly LEFT"
                    errorMessage = null
                }
                2 -> {
                    registrationStep = 3
                    guidanceMessage = "Angle 2/3 (Left) captured ✓ Now tilt head slightly RIGHT"
                    errorMessage = null
                }
                3 -> {
                    guidanceMessage = "Angle 3/3 captured ✓ Fusing 3-angle biometric vectors..."
                    
                    // Aggregate 3 real embeddings into unit-normalized 128-dim template
                    val aggregated = FloatArray(128)
                    for (vec in capturedVectors) {
                        for (i in 0 until 128) {
                            aggregated[i] += vec[i]
                        }
                    }
                    var norm = 0.0
                    for (i in 0 until 128) {
                        aggregated[i] /= capturedVectors.size.toFloat()
                        norm += (aggregated[i] * aggregated[i]).toDouble()
                    }
                    norm = sqrt(norm)
                    if (norm > 0) {
                        for (i in 0 until 128) {
                            aggregated[i] = (aggregated[i] / norm).toFloat()
                        }
                    }

                    // Save frontal portrait photo to local storage
                    val studentsDir = File(context.filesDir, "enrolled_students")
                    if (!studentsDir.exists()) studentsDir.mkdirs()
                    val photoFile = File(studentsDir, "${studentId.trim()}.jpg")
                    withContext(Dispatchers.IO) {
                        val fos = FileOutputStream(photoFile)
                        (capturedFrontFaceBitmap ?: faceCrop).compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        fos.flush()
                        fos.close()
                    }

                    // Store profile in Room SQLite DB
                    val student = app.studentRepository.enrollStudent(
                        studentId = studentId.trim(),
                        name = name.trim(),
                        rollNumber = if (rollNumber.isBlank()) "101" else rollNumber.trim(),
                        course = course.trim(),
                        enrolledSessionIds = listOf(selectedSessionId.trim(), "DL-01"),
                        faceEmbedding = aggregated,
                        photoUri = photoFile.absolutePath
                    )

                    enrolledStudent = student
                    registrationStep = 4
                    guidanceMessage = "✓ Biometric Enrollment Complete! Stored in Room SQLite."
                }
            }
            isProcessingAngle = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "1. Student Biometric Enrollment",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Real-Time Multi-Angle 128-Dim ArcFace Registration",
                            fontSize = 12.sp,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LightSurface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(LightBackground)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Student Information Form Card
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "STUDENT IDENTITY DETAILS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Student Full Name") },
                        placeholder = { Text("e.g. Aman Gupta") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = LightCardBorder
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = studentId,
                            onValueChange = { studentId = it },
                            label = { Text("Student ID") },
                            placeholder = { Text("NCCT1035") },
                            modifier = Modifier.weight(1.2f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )
                        OutlinedTextField(
                            value = rollNumber,
                            onValueChange = { rollNumber = it },
                            label = { Text("Roll No") },
                            placeholder = { Text("135") },
                            modifier = Modifier.weight(0.8f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = course,
                            onValueChange = { course = it },
                            label = { Text("Course Program") },
                            modifier = Modifier.weight(1.2f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )
                        OutlinedTextField(
                            value = selectedSessionId,
                            onValueChange = { selectedSessionId = it },
                            label = { Text("Batch Code") },
                            modifier = Modifier.weight(0.8f),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )
                    }
                }
            }

            // Real Biometric Circular Face Aperture with Connected Progress Ring
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "PHONE-STYLE MULTI-ANGLE REGISTRATION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue,
                        letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    // Guidance Banner
                    Surface(
                        color = if (registrationStep == 4) EmeraldContainer else SkyContainer,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (registrationStep == 4) EmeraldVerified.copy(alpha = 0.4f) else PrimaryBlue.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            text = guidanceMessage,
                            color = if (registrationStep == 4) EmeraldVerified else PrimaryBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }

                    // Biometric Circular Aperture
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer Progress Ring Canvas
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 8.dp.toPx()
                            val diameter = size.minDimension - strokeWidth
                            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)

                            // Background inactive track
                            drawArc(
                                color = Color(0xFFE2E8F0),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = Size(diameter, diameter),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // Active progress arc
                            if (animatedProgress > 0f) {
                                drawArc(
                                    color = if (animatedProgress >= 1f) EmeraldVerified else PrimaryBlue,
                                    startAngle = -90f,
                                    sweepAngle = 360f * animatedProgress,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = Size(diameter, diameter),
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                        }

                        // Circular Camera Preview
                        Box(
                            modifier = Modifier
                                .size(190.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F172A)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (capturedFrontFaceBitmap != null && registrationStep >= 4) {
                                Image(
                                    bitmap = capturedFrontFaceBitmap!!.asImageBitmap(),
                                    contentDescription = "Registered Face",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
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
                                                    val bitmap = imageProxy.toBitmap()
                                                    latestFrameBitmap = bitmap
                                                    imageProxy.close()
                                                }

                                                cameraProvider.unbindAll()
                                                cameraProvider.bindToLifecycle(
                                                    lifecycleOwner,
                                                    cameraSelector,
                                                    preview,
                                                    imageAnalysis
                                                )
                                            } catch (e: Exception) {
                                                Log.e("EnrollmentCamera", "Error binding camera", e)
                                            }
                                        }, ContextCompat.getMainExecutor(ctx))

                                        previewView
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    // 3-Angle Progress Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AngleBadge("1. Frontal", registrationStep >= 1)
                        AngleBadge("2. Left Angle", registrationStep >= 2)
                        AngleBadge("3. Right Angle", registrationStep >= 3)
                    }

                    // Interactive Action Buttons
                    when {
                        registrationStep == 0 -> {
                            Button(
                                onClick = {
                                    if (name.isBlank() || studentId.isBlank()) {
                                        errorMessage = "Please enter student name and ID first."
                                        return@Button
                                    }
                                    errorMessage = null
                                    registrationStep = 1
                                    guidanceMessage = "Angle 1/3: Look straight at camera and tap 'Capture Frontal'"
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("START BIOMETRIC ENROLLMENT", fontWeight = FontWeight.Bold)
                            }
                        }

                        registrationStep in 1..3 -> {
                            Button(
                                onClick = { captureCurrentAngle(registrationStep) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isProcessingAngle
                            ) {
                                if (isProcessingAngle) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text("EXTRACTING 128-DIM EMBEDDING...", fontWeight = FontWeight.Bold)
                                } else {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("CAPTURE ANGLE ($registrationStep / 3)", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        registrationStep >= 4 -> {
                            Button(
                                onClick = {
                                    name = ""
                                    studentId = ""
                                    rollNumber = ""
                                    registrationStep = 0
                                    capturedVectors.clear()
                                    capturedFrontFaceBitmap = null
                                    enrolledStudent = null
                                    guidanceMessage = "Enter student details and tap 'Start Enrollment'"
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldVerified),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("ENROL ANOTHER STUDENT", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    errorMessage?.let {
                        Text(text = it, color = CrimsonAlert, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Local Database Confirmation Card
            if (enrolledStudent != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldVerified.copy(alpha = 0.5f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldVerified)
                            Text(
                                text = "STUDENT ENROLLED IN LOCAL DATABASE",
                                color = EmeraldVerified,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        HorizontalDivider(color = LightCardBorder)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            StudentAvatar(
                                photoUri = enrolledStudent!!.photoUri,
                                size = 64.dp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = enrolledStudent!!.name,
                                    color = TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "ID: ${enrolledStudent!!.studentId}  •  Roll: ${enrolledStudent!!.rollNumber}",
                                    color = PrimaryBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Course: ${enrolledStudent!!.course}  (${enrolledStudent!!.enrolledSessionIds.joinToString(", ")})",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // 128-dim Embedding Vector Preview
                        Surface(
                            color = LightSubtle,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Aggregated 128-Dim ArcFace Biometric Vector Preview:",
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                                val snippet = enrolledStudent!!.faceEmbedding.take(6).joinToString(", ") { String.format("%.3f", it) }
                                Text(
                                    text = "[$snippet, ... +122 dimensions]",
                                    fontSize = 11.sp,
                                    color = PrimaryBlue,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Button(
                            onClick = onEnrollmentComplete,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("VIEW ALL 30 STUDENTS IN DATABASE →", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AngleBadge(label: String, isDone: Boolean) {
    Surface(
        color = if (isDone) EmeraldContainer else LightSubtle,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isDone) EmeraldVerified.copy(alpha = 0.5f) else LightCardBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isDone) EmeraldVerified else TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isDone) FontWeight.Bold else FontWeight.Normal,
                color = if (isDone) EmeraldVerified else TextSecondary
            )
        }
    }
}
