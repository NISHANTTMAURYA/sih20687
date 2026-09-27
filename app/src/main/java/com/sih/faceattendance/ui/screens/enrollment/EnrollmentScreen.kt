package com.sih.faceattendance.ui.screens.enrollment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import java.util.concurrent.Executors
import kotlin.math.abs
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
    var duplicateErrorText by remember { mutableStateOf<String?>(null) }

    // Biometric Modal Popup State
    var showBiometricModal by remember { mutableStateOf(false) }
    var registrationStep by remember { mutableStateOf(1) } // 1: Frontal, 2: Left, 3: Right, 4: Done
    val capturedVectors = remember { mutableStateListOf<FloatArray>() }
    var guidanceMessage by remember { mutableStateOf("Look straight at the camera") }

    // Live Head Pose Tracking State (Auto-Enrollment Engine)
    var faceDetectedInCircle by remember { mutableStateOf(false) }
    var currentYaw by remember { mutableStateOf(0f) }
    var currentPitch by remember { mutableStateOf(0f) }
    var holdCount by remember { mutableStateOf(0) }
    var holdProgress by remember { mutableStateOf(0f) }
    var firstTurnDirection by remember { mutableStateOf(0) } // -1 for left, +1 for right
    var isSuccessAnimation by remember { mutableStateOf(false) }

    // Smooth animated progress value for outer circular ring (0 -> 33% -> 66% -> 100%)
    val animatedProgress by animateFloatAsState(
        targetValue = when (registrationStep) {
            1 -> 0.05f
            2 -> 0.33f
            3 -> 0.66f
            else -> 1.0f
        },
        animationSpec = tween(durationMillis = 450),
        label = "biometric_progress"
    )

    // Function to capture angle and extract embedding
    fun captureCurrentAngle(step: Int, frame: Bitmap, face: DetectedFaceResult) {
        if (isProcessingAngle) return
        isProcessingAngle = true

        scope.launch(Dispatchers.Default) {
            try {
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

                withContext(Dispatchers.Main) {
                    capturedVectors.add(vector)

                    when (step) {
                        1 -> {
                            capturedFrontFaceBitmap = faceCrop
                            registrationStep = 2
                            guidanceMessage = "Frontal captured ✓ Now turn your head SLOWLY LEFT 👈"
                            isProcessingAngle = false
                        }
                        2 -> {
                            registrationStep = 3
                            guidanceMessage = "Left angle captured ✓ Now turn your head SLOWLY RIGHT 👉"
                            isProcessingAngle = false
                        }
                        3 -> {
                            registrationStep = 4
                            guidanceMessage = "All 3 angles captured! Verifying uniqueness..."

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
                                duplicateErrorText = "Biometric Conflict: This face is already enrolled in the local database under '${duplicate.first.name}' (ID: ${duplicate.first.studentId}, ${(duplicate.second * 100).toInt()}% match). Duplicate biometric profiles are prohibited."
                                isProcessingAngle = false
                                return@withContext
                            }

                            // 3. Save frontal photo offline
                            val finalStudentId = if (studentId.isBlank()) "NCCT${System.currentTimeMillis() % 10000}" else studentId.trim()
                            val studentsDir = File(context.filesDir, "enrolled_students")
                            if (!studentsDir.exists()) studentsDir.mkdirs()
                            val photoFile = File(studentsDir, "$finalStudentId.jpg")
                            withContext(Dispatchers.IO) {
                                val fos = FileOutputStream(photoFile)
                                (capturedFrontFaceBitmap ?: faceCrop).compress(Bitmap.CompressFormat.JPEG, 92, fos)
                                fos.flush()
                                fos.close()
                            }

                            // 4. Insert unique student into Room SQLite DB
                            val student = app.studentRepository.enrollStudent(
                                studentId = finalStudentId,
                                name = if (name.isBlank()) "Student $finalStudentId" else name.trim(),
                                rollNumber = if (rollNumber.isBlank()) "101" else rollNumber.trim(),
                                course = selectedCourseOption.courseName,
                                enrolledSessionIds = listOf(selectedCourseOption.batchCode),
                                faceEmbedding = aggregated,
                                photoUri = photoFile.absolutePath
                            )

                            enrolledStudent = student
                            isSuccessAnimation = true
                            guidanceMessage = "Biometric Profile Successfully Registered! 🎉"

                            // Keep success animation for 1.3s then automatically dismiss modal
                            delay(1300)
                            showBiometricModal = false
                            isSuccessAnimation = false
                            isProcessingAngle = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CaptureAngle", "Error capturing angle", e)
                withContext(Dispatchers.Main) {
                    isProcessingAngle = false
                }
            }
        }
    }

    // Function to start a fresh auto-enrollment session
    fun startBiometricEnrollment() {
        if (studentId.isBlank()) {
            studentId = "NCCT${System.currentTimeMillis() % 10000}"
        }
        if (rollNumber.isBlank()) {
            rollNumber = "101"
        }
        if (name.isBlank()) {
            name = "Student ${studentId.takeLast(4)}"
        }

        capturedVectors.clear()
        registrationStep = 1
        holdCount = 0
        holdProgress = 0f
        firstTurnDirection = 0
        isProcessingAngle = false
        isSuccessAnimation = false
        duplicateErrorText = null
        guidanceMessage = "Look straight at the camera"
        showBiometricModal = true
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
                            text = "Auto-Detect Multi-Angle Face Registration (FaceID Style)",
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
            // Institutional Banner
            Surface(
                color = PrimaryBlueContainer,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryBlue.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AUTO-GUIDED BIOMETRIC REGISTRATION",
                            color = PrimaryBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Automatically captures Frontal, Left, and Right angles as you turn your head. Extracts 128-dim ArcFace embedding with duplicate prevention.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Student Metadata Form Card
            Card(
                colors = CardDefaults.cardColors(containerColor = LightSurface),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "STUDENT PROFILE DETAILS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue,
                        letterSpacing = 0.5.sp
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full Name") },
                        placeholder = { Text("e.g. Nishant Maurya") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = TextSecondary) },
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = LightSurface,
                            unfocusedContainerColor = LightSurface,
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = LightCardBorder
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = studentId,
                            onValueChange = { studentId = it },
                            label = { Text("Student ID") },
                            placeholder = { Text("e.g. NCCT1031") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null, tint = TextSecondary) },
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LightSurface,
                                unfocusedContainerColor = LightSurface,
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )

                        OutlinedTextField(
                            value = rollNumber,
                            onValueChange = { rollNumber = it },
                            label = { Text("Roll No.") },
                            placeholder = { Text("e.g. 131") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, tint = TextSecondary) },
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LightSurface,
                                unfocusedContainerColor = LightSurface,
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )
                    }

                    // COURSE SELECTION DROPDOWN
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
                            leadingIcon = { Icon(Icons.Default.School, contentDescription = null, tint = PrimaryBlue) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = LightSurface,
                                unfocusedContainerColor = LightSurface,
                                focusedBorderColor = PrimaryBlue,
                                unfocusedBorderColor = LightCardBorder
                            )
                        )

                        ExposedDropdownMenu(
                            expanded = isCourseDropdownExpanded,
                            onDismissRequest = { isCourseDropdownExpanded = false },
                            modifier = Modifier.background(LightSurface)
                        ) {
                            DEFAULT_COURSES.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(option.courseName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                                            Text("Batch Code: ${option.batchCode}", fontSize = 11.sp, color = PrimaryBlue)
                                        }
                                    },
                                    onClick = {
                                        selectedCourseOption = option
                                        isCourseDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Start Registration Button
                    Button(
                        onClick = { startBiometricEnrollment() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Face, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "START AUTO FACE REGISTRATION",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Duplicate Conflict Alert (if previously triggered)
            if (duplicateErrorText != null) {
                Surface(
                    color = CrimsonContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = CrimsonAlert)
                        Text(
                            text = duplicateErrorText!!,
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

    // IMMERSIVE AUTO-DETECT BIOMETRIC REGISTRATION MODAL POPUP
    if (showBiometricModal) {
        Dialog(
            onDismissRequest = { showBiometricModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                shape = RoundedCornerShape(24.dp),
                color = LightSurface,
                shadowElevation = 10.dp
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
                                text = "Auto Biometric Enrollment",
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

                    // Dynamic Guidance Banner
                    Surface(
                        color = when {
                            isSuccessAnimation -> EmeraldContainer
                            faceDetectedInCircle -> SkyContainer
                            else -> AmberContainer
                        },
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when {
                                isSuccessAnimation -> EmeraldVerified.copy(alpha = 0.5f)
                                faceDetectedInCircle -> PrimaryBlue.copy(alpha = 0.4f)
                                else -> AmberOffline.copy(alpha = 0.4f)
                            }
                        )
                    ) {
                        Text(
                            text = guidanceMessage,
                            color = when {
                                isSuccessAnimation -> EmeraldVerified
                                faceDetectedInCircle -> PrimaryBlue
                                else -> AmberOffline
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }

                    // BIG CIRCULAR CAMERA PREVIEW (280dp DIAMETER) WITH AUTO-HOLD ANIMATION
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Outer Overall Progress Ring (0% -> 33% -> 66% -> 100%)
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

                            // Step Progress
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

                            // Dynamic Hold-Steady Progress Ring (Inner thin ring)
                            if (holdProgress > 0f && !isSuccessAnimation) {
                                val holdStroke = 4.dp.toPx()
                                val holdDiameter = diameter - strokeWidth - 6.dp.toPx()
                                val holdTopLeft = Offset((size.width - holdDiameter) / 2, (size.height - holdDiameter) / 2)
                                drawArc(
                                    color = EmeraldVerified,
                                    startAngle = -90f,
                                    sweepAngle = 360f * holdProgress,
                                    useCenter = false,
                                    topLeft = holdTopLeft,
                                    size = Size(holdDiameter, holdDiameter),
                                    style = Stroke(width = holdStroke, cap = StrokeCap.Round)
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
                            var isAnalyzingFrame by remember { mutableStateOf(false) }

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

                                            val analysisExecutor = Executors.newSingleThreadExecutor()

                                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                                try {
                                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                                    val rawBitmap = imageProxy.toBitmap()
                                                    imageProxy.close()

                                                    // Rotate to upright & mirror horizontally for front camera
                                                    val matrix = Matrix()
                                                    if (rotation != 0) {
                                                        matrix.postRotate(rotation.toFloat())
                                                    }
                                                    matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
                                                    val upright = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)

                                                    latestFrameBitmap = upright

                                                    // Skip frame if busy or already finished
                                                    if (isAnalyzingFrame || isProcessingAngle || isSuccessAnimation || !showBiometricModal) {
                                                        return@setAnalyzer
                                                    }

                                                    isAnalyzingFrame = true

                                                    scope.launch(Dispatchers.Default) {
                                                        try {
                                                            val faces = app.faceDetectorEngine.detectFaces(upright, 0)
                                                            withContext(Dispatchers.Main) {
                                                                if (faces.isEmpty()) {
                                                                    faceDetectedInCircle = false
                                                                    currentYaw = 0f
                                                                    currentPitch = 0f
                                                                    holdCount = 0
                                                                    holdProgress = 0f
                                                                    guidanceMessage = "Position your face inside the circle"
                                                                } else {
                                                                    faceDetectedInCircle = true
                                                                    val face = faces[0]
                                                                    currentYaw = face.headEulerAngleY
                                                                    currentPitch = face.headEulerAngleX

                                                                    // Automatic progression based on current step
                                                                    when (registrationStep) {
                                                                        1 -> {
                                                                            // Frontal Target: yaw between -12° and +12°, pitch between -18° and +18°
                                                                            if (abs(currentYaw) <= 12f && abs(currentPitch) <= 18f) {
                                                                                guidanceMessage = "Hold steady... Capturing Frontal"
                                                                                holdCount++
                                                                                holdProgress = (holdCount / 3f).coerceIn(0f, 1f)
                                                                                if (holdCount >= 3) {
                                                                                    holdCount = 0
                                                                                    holdProgress = 0f
                                                                                    captureCurrentAngle(1, upright, face)
                                                                                }
                                                                            } else {
                                                                                holdCount = 0
                                                                                holdProgress = 0f
                                                                                guidanceMessage = "Look straight at the camera"
                                                                            }
                                                                        }
                                                                        2 -> {
                                                                            // Turn Head Left: User turns left (yaw < -13° or abs(yaw) >= 13°)
                                                                            val isTurningSide = currentYaw < -13f || abs(currentYaw) >= 13f
                                                                            if (isTurningSide) {
                                                                                firstTurnDirection = if (currentYaw < 0) -1 else 1
                                                                                guidanceMessage = "Hold steady... Capturing Left Angle"
                                                                                holdCount++
                                                                                holdProgress = (holdCount / 3f).coerceIn(0f, 1f)
                                                                                if (holdCount >= 3) {
                                                                                    holdCount = 0
                                                                                    holdProgress = 0f
                                                                                    captureCurrentAngle(2, upright, face)
                                                                                }
                                                                            } else {
                                                                                holdCount = 0
                                                                                holdProgress = 0f
                                                                                guidanceMessage = "Now slowly turn your head LEFT 👈"
                                                                            }
                                                                        }
                                                                        3 -> {
                                                                            // Turn Head Right: User turns opposite direction (opposite sign, abs >= 13°)
                                                                            val isOppositeSide = if (firstTurnDirection != 0) {
                                                                                (currentYaw * firstTurnDirection) < -10f
                                                                            } else {
                                                                                currentYaw > 13f || abs(currentYaw) >= 13f
                                                                            }

                                                                            if (isOppositeSide) {
                                                                                guidanceMessage = "Hold steady... Capturing Right Angle"
                                                                                holdCount++
                                                                                holdProgress = (holdCount / 3f).coerceIn(0f, 1f)
                                                                                if (holdCount >= 3) {
                                                                                    holdCount = 0
                                                                                    holdProgress = 0f
                                                                                    captureCurrentAngle(3, upright, face)
                                                                                }
                                                                            } else {
                                                                                holdCount = 0
                                                                                holdProgress = 0f
                                                                                guidanceMessage = "Now slowly turn your head RIGHT 👉"
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } finally {
                                                            isAnalyzingFrame = false
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    imageProxy.close()
                                                    isAnalyzingFrame = false
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
                                            Log.e("ModalEnrollmentCamera", "Error binding camera", e)
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))

                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Success Overlay with Checkmark
                            if (isSuccessAnimation) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(EmeraldVerified.copy(alpha = 0.85f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Success",
                                        tint = Color.White,
                                        modifier = Modifier.size(72.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Live Head Angle Pill Feedback
                    Surface(
                        color = LightSubtle,
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LightCardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (faceDetectedInCircle) Icons.Default.Face else Icons.Default.FaceRetouchingOff,
                                contentDescription = null,
                                tint = if (faceDetectedInCircle) PrimaryBlue else TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            val poseText = when {
                                !faceDetectedInCircle -> "Looking for face..."
                                abs(currentYaw) <= 10f -> "Frontal (0°)"
                                currentYaw < -10f -> "Left (${currentYaw.toInt()}°)"
                                else -> "Right (+${currentYaw.toInt()}°)"
                            }
                            Text(
                                text = "Head Pose: $poseText",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (faceDetectedInCircle) TextPrimary else TextMuted
                            )
                        }
                    }

                    // 3 Angle Step Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        AngleBadge("1. Frontal", registrationStep > 1)
                        AngleBadge("2. Left Angle", registrationStep > 2)
                        AngleBadge("3. Right Angle", registrationStep > 3)
                    }

                    // Duplicate Conflict Error in Modal
                    if (duplicateErrorText != null) {
                        Surface(
                            color = CrimsonContainer,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonAlert.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = duplicateErrorText!!,
                                color = CrimsonAlert,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Action Controls: Restart & Manual Fallback Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                capturedVectors.clear()
                                registrationStep = 1
                                holdCount = 0
                                holdProgress = 0f
                                firstTurnDirection = 0
                                isProcessingAngle = false
                                duplicateErrorText = null
                                guidanceMessage = "Look straight at the camera"
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset", fontSize = 13.sp)
                        }

                        // Manual Capture Override Button
                        Button(
                            onClick = {
                                val frame = latestFrameBitmap
                                if (frame != null) {
                                    scope.launch {
                                        val faces = app.faceDetectorEngine.detectFaces(frame, 0)
                                        if (faces.isNotEmpty()) {
                                            captureCurrentAngle(registrationStep, frame, faces[0])
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(2f),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            enabled = !isProcessingAngle && registrationStep <= 3
                        ) {
                            if (isProcessingAngle) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("Processing...", fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Manual Capture (${registrationStep}/3)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
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
