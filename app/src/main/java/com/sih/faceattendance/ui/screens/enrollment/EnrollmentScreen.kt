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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.sih.faceattendance.AttendanceApplication
import com.sih.faceattendance.core.*
import com.sih.faceattendance.data.local.entities.StudentEntity
import com.sih.faceattendance.ui.components.StudentAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

data class CourseBatchOption(val courseName: String, val batchCode: String)

val DEFAULT_COURSES = listOf(
    CourseBatchOption("Digital Literacy", "DL-01"),
    CourseBatchOption("Cooperative Management", "CM-02"),
    CourseBatchOption("Entrepreneurship Development", "EN-03"),
    CourseBatchOption("Agri-Cooperative Banking", "AB-04"),
    CourseBatchOption("Rural Credit & Finance", "RC-05")
)

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

    // Course & Batch selection state with dropdown
    var selectedCourseOption by remember { mutableStateOf(DEFAULT_COURSES[0]) }
    var isCourseDropdownExpanded by remember { mutableStateOf(false) }

    var latestFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var capturedFrontFaceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var enrolledStudent by remember { mutableStateOf<StudentEntity?>(null) }
    var isProcessingAngle by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Biometric Modal Popup State
    var showBiometricModal by remember { mutableStateOf(false) }
    var registrationStep by remember { mutableStateOf(1) } // 1: Frontal, 2: Left, 3: Right
    val capturedVectors = remember { mutableStateListOf<FloatArray>() }
    var guidanceMessage by remember { mutableStateOf("Angle 1/3: Position face in circle and tap Capture") }

    // Smooth animated progress value for outer circular ring (33% -> 66% -> 100%)
    val animatedProgress by animateFloatAsState(
        targetValue = when (registrationStep) {
            1 -> 0.33f
            2 -> 0.66f
            else -> 1.0f
        },
        animationSpec = tween(durationMillis = 400),
        label = "biometric_progress"
    )

    // Function to capture angle and extract embedding
    fun captureCurrentAngle(step: Int) {
        if (isProcessingAngle) return
        val frame = latestFrameBitmap
        if (frame == null) {
            errorMessage = "Camera frame not ready yet. Please hold steady."
            return
        }

        isProcessingAngle = true
        scope.launch {
            val faces = app.faceDetectorEngine.detectFaces(frame)
            if (faces.isEmpty()) {
                errorMessage = "No face detected in circle! Align face inside frame."
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

            // Extract 128-dim ArcFace embedding
            val vector = app.faceEmbeddingEngine.extractEmbedding(faceCrop)
            capturedVectors.add(vector)

            when (step) {
                1 -> {
                    capturedFrontFaceBitmap = faceCrop
                    registrationStep = 2
                    guidanceMessage = "Angle 1/3 (Frontal) captured ✓ Now tilt head slightly LEFT"
                    errorMessage = null
                }
                2 -> {
                    registrationStep = 3
                    guidanceMessage = "Angle 2/3 (Left) captured ✓ Now tilt head slightly RIGHT"
                    errorMessage = null
                }
                3 -> {
                    guidanceMessage = "Angle 3/3 captured ✓ Checking for duplicate biometrics..."

                    // 1. Fuse the 3 embeddings into an averaged unit-norm vector
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

                    // 2. DUPLICATE FACE VALIDATION: Prevent one person from enrolling under multiple IDs/names!
                    val duplicate = app.studentRepository.findDuplicateFace(aggregated, threshold = 0.72f)
                    if (duplicate != null) {
                        errorMessage = "Biometric Conflict: This face is already enrolled under '${duplicate.first.name}' (ID: ${duplicate.first.studentId}, ${(duplicate.second * 100).toInt()}% match). Duplicate face registration is prohibited."
                        showBiometricModal = false
                        isProcessingAngle = false
                        return@launch
                    }

                    // 3. Save frontal photo
                    val studentsDir = File(context.filesDir, "enrolled_students")
                    if (!studentsDir.exists()) studentsDir.mkdirs()
                    val photoFile = File(studentsDir, "${studentId.trim()}.jpg")
                    withContext(Dispatchers.IO) {
                        val fos = FileOutputStream(photoFile)
                        (capturedFrontFaceBitmap ?: faceCrop).compress(Bitmap.CompressFormat.JPEG, 92, fos)
                        fos.flush()
                        fos.close()
                    }

                    // 4. Insert unique student into Room SQLite DB
                    val student = app.studentRepository.enrollStudent(
                        studentId = studentId.trim(),
                        name = name.trim(),
                        rollNumber = if (rollNumber.isBlank()) "101" else rollNumber.trim(),
                        course = selectedCourseOption.courseName,
                        enrolledSessionIds = listOf(selectedCourseOption.batchCode),
                        faceEmbedding = aggregated,
                        photoUri = photoFile.absolutePath
                    )

                    enrolledStudent = student
                    showBiometricModal = false
                    errorMessage = null
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
                            text = "Offline Biometric Registration with Duplicate Protection",
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
                        text = "STUDENT IDENTITY & COURSE DETAILS",
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

                    // COURSE & BATCH SELECTION DROPDOWN
                    ExposedDropdownMenuBox(
                        expanded = isCourseDropdownExpanded,
                        onExpandedChange = { isCourseDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = "${selectedCourseOption.courseName} (${selectedCourseOption.batchCode})",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Enrolled Course & Batch") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isCourseDropdownExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = isCourseDropdownExpanded,
                            onDismissRequest = { isCourseDropdownExpanded = false }
                        ) {
                            DEFAULT_COURSES.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.courseName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                            Text("Batch Code: ${option.batchCode}", fontSize = 11.sp, color = TextSecondary)
                                        }
                                    },
                                    onClick = {
                                        selectedCourseOption = option
                                        isCourseDropdownExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.School, contentDescription = null, tint = PrimaryBlue)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Open Biometric Modal Button
                    Button(
                        onClick = {
                            if (name.isBlank() || studentId.isBlank()) {
                                errorMessage = "Please enter Student Full Name and Student ID first."
                                return@Button
                            }
                            errorMessage = null
                            capturedVectors.clear()
                            registrationStep = 1
                            guidanceMessage = "Angle 1/3: Position face in circle and tap Capture"
                            showBiometricModal = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (enrolledStudent != null) "RE-REGISTER BIOMETRIC FACE" else "OPEN BIOMETRIC CAMERA SCANNER",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    errorMessage?.let {
                        Text(
                            text = it,
                            color = CrimsonAlert,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Successfully Enrolled Confirmation Card
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
                            Text("VIEW ALL STUDENTS IN DATABASE →", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // IMMERSIVE BIOMETRIC REGISTRATION MODAL POPUP WITH BIG CIRCLE PREVIEW
    if (showBiometricModal) {
        Dialog(
            onDismissRequest = { showBiometricModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(20.dp),
                color = LightSurface,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Bar with Student Name and Close Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Biometric Enrollment",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Student: $name ($studentId)",
                                fontSize = 12.sp,
                                color = PrimaryBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = { showBiometricModal = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    // Guidance Banner
                    Surface(
                        color = SkyContainer,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = guidanceMessage,
                            color = PrimaryBlue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    // BIG CIRCULAR CAMERA PREVIEW (280dp DIAMETER)
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer Progress Ring Canvas
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 10.dp.toPx()
                            val diameter = size.minDimension - strokeWidth
                            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)

                            // Track
                            drawArc(
                                color = Color(0xFFE2E8F0),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = Size(diameter, diameter),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // Progress
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

                        // Circular CameraX Aperture
                        Box(
                            modifier = Modifier
                                .size(230.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0F172A)),
                            contentAlignment = Alignment.Center
                        ) {
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
                                            Log.e("ModalEnrollmentCamera", "Error binding camera", e)
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))

                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // 3 Angle Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AngleBadge("1. Frontal", registrationStep >= 1)
                        AngleBadge("2. Left Tilt", registrationStep >= 2)
                        AngleBadge("3. Right Tilt", registrationStep >= 3)
                    }

                    // Capture Step Action Button
                    Button(
                        onClick = { captureCurrentAngle(registrationStep) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isProcessingAngle
                    ) {
                        if (isProcessingAngle) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("EXTRACTING EMBEDDING...", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "CAPTURE STEP ($registrationStep / 3)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
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
