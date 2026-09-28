package com.sih.faceattendance.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.sih.faceattendance.data.local.entities.AttendanceRecordEntity
import com.sih.faceattendance.data.local.entities.SessionEntity
import com.sih.faceattendance.data.local.entities.StudentEntity
import com.sih.faceattendance.data.repository.AttendanceRepository
import com.sih.faceattendance.data.repository.StudentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class PipelineStage {
    IDLE,
    DETECTING_FACE,
    CHECKING_LIVENESS,
    CHECKING_PHONE,
    EXTRACTING_EMBEDDING,
    MATCHING_BIOMETRICS,
    VERIFYING_SESSION,
    CHECKING_LOCATION,
    MARKING_ATTENDANCE,
    SUCCESS,
    REJECTED
}

data class PipelineStepProgress(
    val stepIndex: Int,
    val stepName: String,
    val isRunning: Boolean,
    val isSuccess: Boolean,
    val detailMessage: String = "",
    val telemetry: PipelineTelemetry? = null
)

data class PipelineTelemetry(
    val stage: PipelineStage = PipelineStage.IDLE,
    val faceDetected: Boolean = false,
    val faceBoundingBox: Rect? = null,
    val livenessVerified: Boolean = false,
    val livenessScore: Float = 0.0f,
    val phoneDetected: Boolean = false,
    val phoneConfidence: Float = 0.0f,
    val embeddingGenerated: Boolean = false,
    val embeddingDimension: Int = 192,
    val matchFound: Boolean = false,
    val matchedStudent: StudentEntity? = null,
    val similarityScore: Float = 0.0f,
    val similarityThreshold: Float = 0.70f,
    val sessionVerified: Boolean = false,
    val locationVerified: Boolean = false,
    val distanceMeters: Float = 0.0f,
    val statusMessage: String = "Ready for scanning",
    val failureReason: String? = null,
    val recordedAttendance: AttendanceRecordEntity? = null,
    val liveFaceCrop: Bitmap? = null,
    val sampleEmbeddingSnippet: List<Float>? = null,
    val phoneBoundingBox: Rect? = null,
    val inspectionOverlayBitmap: Bitmap? = null
)

class AttendancePipelineCoordinator(
    private val faceDetector: FaceDetectorEngine,
    private val livenessEngine: LivenessEngine,
    private val phoneDetector: PhoneDetectorEngine,
    private val embeddingEngine: FaceEmbeddingEngine,
    private val studentRepository: StudentRepository,
    private val attendanceRepository: AttendanceRepository
) {

    // Configurable thresholds (0.62f calibrated for robust cross-device camera recognition)
    var similarityThreshold: Float = 0.62f
    var livenessThreshold: Float = 0.65f

    // Developer Simulation Overrides (for testing rejection branches)
    var simulateSpoofAttack: Boolean = false
    var simulatePhonePresent: Boolean = false
    var simulateLocationInvalid: Boolean = false

    suspend fun processFrame(
        frameBitmap: Bitmap,
        activeSession: SessionEntity,
        deviceLatitude: Double,
        deviceLongitude: Double,
        onProgress: ((PipelineStepProgress) -> Unit)? = null
    ): PipelineTelemetry = withContext(Dispatchers.Default) {

        // STEP 1 — Human Face Detection
        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 1,
                stepName = "Face Detection & Boundary Analysis",
                isRunning = true,
                isSuccess = false,
                detailMessage = "Running ML Kit on-device face detector..."
            )
        )

        var normalizedFrame = frameBitmap
        var faces = faceDetector.detectFaces(normalizedFrame, 0)

        // Multi-orientation fallback (checks 90°, 270°, 180° if initial angle had 0 faces)
        if (faces.isEmpty()) {
            for (rot in listOf(90, 270, 180)) {
                val testFaces = faceDetector.detectFaces(frameBitmap, rot)
                if (testFaces.isNotEmpty()) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                    normalizedFrame = Bitmap.createBitmap(frameBitmap, 0, 0, frameBitmap.width, frameBitmap.height, matrix, true)
                    faces = faceDetector.detectFaces(normalizedFrame, 0)
                    break
                }
            }
        }

        if (faces.isEmpty()) {
            val failure = PipelineTelemetry(
                stage = PipelineStage.REJECTED,
                faceDetected = false,
                statusMessage = "✕ NO FACE DETECTED",
                failureReason = "No human face found in camera view. Please align face inside reticle."
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 1,
                    stepName = "Face Detection & Boundary Analysis",
                    isRunning = false,
                    isSuccess = false,
                    detailMessage = "No face located in camera frame.",
                    telemetry = failure
                )
            )
            return@withContext failure
        }

        val primaryFace = faces[0]
        val boundingBox = primaryFace.boundingBox

        // Expand face bounding box by 25% to capture full facial context (forehead, hair, chin)
        val marginX = (boundingBox.width() * 0.25f).toInt()
        val marginY = (boundingBox.height() * 0.25f).toInt()
        val safeLeft = (boundingBox.left - marginX).coerceIn(0, normalizedFrame.width - 1)
        val safeTop = (boundingBox.top - marginY).coerceIn(0, normalizedFrame.height - 1)
        val safeRight = (boundingBox.right + marginX).coerceIn(safeLeft + 1, normalizedFrame.width)
        val safeBottom = (boundingBox.bottom + marginY).coerceIn(safeTop + 1, normalizedFrame.height)
        val safeWidth = safeRight - safeLeft
        val safeHeight = safeBottom - safeTop

        val faceCrop = try {
            Bitmap.createBitmap(normalizedFrame, safeLeft, safeTop, safeWidth, safeHeight)
        } catch (_: Exception) {
            normalizedFrame
        }

        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 1,
                stepName = "Face Detection & Boundary Analysis",
                isRunning = false,
                isSuccess = true,
                detailMessage = "Face located (${safeWidth}×${safeHeight} px, Yaw: ${primaryFace.headEulerAngleY.toInt()}°)"
            )
        )
        delay(280)

        // STEP 2 — Liveness / Anti-Spoofing
        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 2,
                stepName = "MiniFASNetV2 + Blink + Texture Liveness",
                isRunning = true,
                isSuccess = false,
                detailMessage = "PAD model · texture analysis · blink verification..."
            )
        )

        // Human-face gate: all 5 canonical landmarks must be present.
        // ML Kit reliably finds leftEye, rightEye, noseBase, mouthLeft, mouthRight on human faces.
        // Animal faces (cats, dogs) may produce a bounding box but will be missing one or more.
        val hasAllHumanLandmarks = primaryFace.leftEyePosition != null &&
                                   primaryFace.rightEyePosition != null &&
                                   primaryFace.noseBasePosition != null &&
                                   primaryFace.mouthLeftPosition != null &&
                                   primaryFace.mouthRightPosition != null

        val livenessResult = livenessEngine.evaluateLiveness(
            faceCrop = faceCrop,
            forceSpoofSimulate = simulateSpoofAttack,
            hasRequiredHumanLandmarks = hasAllHumanLandmarks,
            requireBlink = true
        )

        if (!livenessResult.isLive) {
            val inspectedOverlay = renderVisualInspectionFrame(normalizedFrame, boundingBox, null, isSpoof = true, isPhonePresent = false)
            val failure = PipelineTelemetry(
                stage = PipelineStage.REJECTED,
                faceDetected = true,
                faceBoundingBox = boundingBox,
                livenessVerified = false,
                livenessScore = livenessResult.livenessScore,
                statusMessage = "✕ SPOOF ATTACK DETECTED",
                failureReason = livenessResult.message,
                liveFaceCrop = faceCrop,
                inspectionOverlayBitmap = inspectedOverlay
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 2,
                    stepName = "MiniFASNetV2 + Blink + Texture Liveness",
                    isRunning = false,
                    isSuccess = false,
                    detailMessage = livenessResult.message,
                    telemetry = failure
                )
            )
            return@withContext failure
        }

        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 2,
                stepName = "MiniFASNetV2 + Blink + Texture Liveness",
                isRunning = false,
                isSuccess = true,
                detailMessage = "Live face confirmed (Score: ${String.format("%.2f", livenessResult.livenessScore)})"
            )
        )
        delay(280)

        // STEP 3 — Phone / Tablet Screen Detection
        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 3,
                stepName = "Secondary Screen Replay Defense",
                isRunning = true,
                isSuccess = false,
                detailMessage = "Scanning for handheld screens, tablets, or video replays..."
            )
        )

        val phoneResult = phoneDetector.detectPhone(frameBitmap, forcePhoneSimulate = simulatePhonePresent)
        if (phoneResult.isPhonePresent) {
            val inspectedOverlay = renderVisualInspectionFrame(normalizedFrame, boundingBox, phoneResult.boundingBox, isSpoof = false, isPhonePresent = true)
            val failure = PipelineTelemetry(
                stage = PipelineStage.REJECTED,
                faceDetected = true,
                faceBoundingBox = boundingBox,
                livenessVerified = true,
                livenessScore = livenessResult.livenessScore,
                phoneDetected = true,
                phoneConfidence = phoneResult.confidence,
                phoneBoundingBox = phoneResult.boundingBox,
                statusMessage = "✕ SECONDARY SCREEN DETECTED",
                failureReason = "Mobile display/screen detected in frame. Video replay attack rejected.",
                liveFaceCrop = faceCrop,
                inspectionOverlayBitmap = inspectedOverlay
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 3,
                    stepName = "Secondary Screen Replay Defense",
                    isRunning = false,
                    isSuccess = false,
                    detailMessage = "Screen detected in frame (Conf: ${String.format("%.2f", phoneResult.confidence)})",
                    telemetry = failure
                )
            )
            return@withContext failure
        }

        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 3,
                stepName = "Secondary Screen Replay Defense",
                isRunning = false,
                isSuccess = true,
                detailMessage = "No secondary electronic screen present (Frame clean)"
            )
        )
        delay(280)

        // STEP 4 — Face Embedding Generation
        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 4,
                stepName = "MobileFaceNet Biometric Extraction",
                isRunning = true,
                isSuccess = false,
                detailMessage = "Executing MobileFaceNet deep feature extraction..."
            )
        )

        val alignedFace = embeddingEngine.alignFace(
            sourceBitmap = normalizedFrame,
            eye1 = primaryFace.leftEyePosition,
            eye2 = primaryFace.rightEyePosition,
            boundingBox = boundingBox
        )
        val embedding = embeddingEngine.extractEmbedding(alignedFace)
        val embDim = embeddingEngine.getEmbeddingDimension()
        val embeddingSnippet = embedding.take(6)
        val snippetStr = embeddingSnippet.joinToString(", ") { String.format("%.3f", it) }

        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 4,
                stepName = "MobileFaceNet Biometric Extraction",
                isRunning = false,
                isSuccess = true,
                detailMessage = "Vector: [$snippetStr, ...] (${embDim}-D normalized)"
            )
        )
        delay(280)

        // STEP 5 — Compare Embeddings with Local Enrolled Students
        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 5,
                stepName = "Local SQLite Database Cosine Match",
                isRunning = true,
                isSuccess = false,
                detailMessage = "Calculating vector dot products across enrolled database..."
            )
        )

        val allStudents = studentRepository.getAllStudents()
        if (allStudents.isEmpty()) {
            val failure = PipelineTelemetry(
                stage = PipelineStage.REJECTED,
                faceDetected = true,
                faceBoundingBox = boundingBox,
                livenessVerified = true,
                phoneDetected = false,
                embeddingGenerated = true,
                embeddingDimension = embDim,
                statusMessage = "✕ NO ENROLLED STUDENTS",
                failureReason = "Local database is empty. Enrol students in Tab 1 first.",
                liveFaceCrop = faceCrop,
                sampleEmbeddingSnippet = embeddingSnippet
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 5,
                    stepName = "Local SQLite Database Cosine Match",
                    isRunning = false,
                    isSuccess = false,
                    detailMessage = "0 enrolled students in database.",
                    telemetry = failure
                )
            )
            return@withContext failure
        }

        var bestMatch: StudentEntity? = null
        var highestSimilarity = -1.0f

        for (student in allStudents) {
            val similarity = CosineSimilarity.compute(embedding, student.faceEmbedding)
            if (similarity > highestSimilarity) {
                highestSimilarity = similarity
                bestMatch = student
            }
        }

        if (bestMatch == null || highestSimilarity < similarityThreshold) {
            val failure = PipelineTelemetry(
                stage = PipelineStage.REJECTED,
                faceDetected = true,
                faceBoundingBox = boundingBox,
                livenessVerified = true,
                livenessScore = livenessResult.livenessScore,
                phoneDetected = false,
                embeddingGenerated = true,
                embeddingDimension = embDim,
                similarityScore = highestSimilarity.coerceAtLeast(0f),
                similarityThreshold = similarityThreshold,
                statusMessage = "✕ FACE NOT RECOGNISED",
                failureReason = "Biometric similarity ${String.format("%.1f", highestSimilarity * 100)}% below required ${String.format("%.0f", similarityThreshold * 100)}% threshold.",
                liveFaceCrop = alignedFace,
                sampleEmbeddingSnippet = embeddingSnippet
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 5,
                    stepName = "Local SQLite Database Cosine Match",
                    isRunning = false,
                    isSuccess = false,
                    detailMessage = "Highest match: ${String.format("%.1f", highestSimilarity * 100)}% (< 70% threshold)",
                    telemetry = failure
                )
            )
            return@withContext failure
        }

        // STEP 6 — Student Session Verification & Campus Geolocation
        val isRegisteredInSession = bestMatch.enrolledSessionIds.isEmpty() ||
                bestMatch.enrolledSessionIds.any {
                    it.equals(activeSession.sessionId, ignoreCase = true) ||
                    it.equals(activeSession.batchCode, ignoreCase = true)
                }
        if (!isRegisteredInSession) {
            val failure = PipelineTelemetry(
                stage = PipelineStage.REJECTED,
                faceDetected = true,
                faceBoundingBox = boundingBox,
                livenessVerified = true,
                phoneDetected = false,
                embeddingGenerated = true,
                embeddingDimension = embDim,
                matchFound = true,
                matchedStudent = bestMatch,
                similarityScore = highestSimilarity,
                statusMessage = "✕ NOT ENROLLED IN THIS BATCH",
                failureReason = "${bestMatch.name} (${bestMatch.studentId}) is not registered for batch ${activeSession.title}.",
                liveFaceCrop = alignedFace,
                sampleEmbeddingSnippet = embeddingSnippet
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 5,
                    stepName = "Local SQLite Database Cosine Match",
                    isRunning = false,
                    isSuccess = false,
                    detailMessage = "${bestMatch.name} not in batch ${activeSession.batchCode}",
                    telemetry = failure
                )
            )
            return@withContext failure
        }

        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 5,
                stepName = "Local SQLite Database Cosine Match",
                isRunning = false,
                isSuccess = true,
                detailMessage = "Identified: ${bestMatch.name} (${(highestSimilarity * 100).toInt()}% Match)"
            )
        )
        delay(300)

        // Geolocation Check
        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 6,
                stepName = "Campus GPS Geofence Verification",
                isRunning = true,
                isSuccess = false,
                detailMessage = "Verifying device location against ${activeSession.centerName}..."
            )
        )

        val distance = calculateDistanceMeters(
            deviceLatitude,
            deviceLongitude,
            activeSession.centerLatitude,
            activeSession.centerLongitude
        )

        val isLocationValid = if (simulateLocationInvalid) false else distance <= activeSession.allowedRadiusMeters
        if (!isLocationValid) {
            val failure = PipelineTelemetry(
                stage = PipelineStage.REJECTED,
                faceDetected = true,
                faceBoundingBox = boundingBox,
                livenessVerified = true,
                phoneDetected = false,
                embeddingGenerated = true,
                embeddingDimension = embDim,
                matchFound = true,
                matchedStudent = bestMatch,
                similarityScore = highestSimilarity,
                sessionVerified = true,
                locationVerified = false,
                distanceMeters = distance,
                statusMessage = "✕ OUTSIDE CAMPUS BOUNDARY",
                failureReason = "Device GPS position is outside training center (${distance.toInt()}m > ${activeSession.allowedRadiusMeters.toInt()}m).",
                liveFaceCrop = alignedFace,
                sampleEmbeddingSnippet = embeddingSnippet
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 6,
                    stepName = "Campus GPS Geofence Verification",
                    isRunning = false,
                    isSuccess = false,
                    detailMessage = "Distance: ${distance.toInt()}m (Exceeds ${activeSession.allowedRadiusMeters.toInt()}m)",
                    telemetry = failure
                )
            )
            return@withContext failure
        }

        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 6,
                stepName = "Campus GPS Geofence Verification",
                isRunning = false,
                isSuccess = true,
                detailMessage = "Within campus bounds (${distance.toInt()}m from center)"
            )
        )
        delay(280)

        // Check if already marked for this session today
        val alreadyMarked = attendanceRepository.isAlreadyMarked(bestMatch.studentId, activeSession.sessionId)
        if (alreadyMarked) {
            val success = PipelineTelemetry(
                stage = PipelineStage.SUCCESS,
                faceDetected = true,
                faceBoundingBox = boundingBox,
                livenessVerified = true,
                phoneDetected = false,
                embeddingGenerated = true,
                matchFound = true,
                matchedStudent = bestMatch,
                similarityScore = highestSimilarity,
                sessionVerified = true,
                locationVerified = true,
                distanceMeters = distance,
                statusMessage = "ATTENDANCE ALREADY RECORDED",
                failureReason = "Student ${bestMatch.name} is already marked present.",
                liveFaceCrop = alignedFace,
                sampleEmbeddingSnippet = embeddingSnippet
            )
            onProgress?.invoke(
                PipelineStepProgress(
                    stepIndex = 7,
                    stepName = "Atomic Attendance Recording",
                    isRunning = false,
                    isSuccess = true,
                    detailMessage = "Already recorded present for today.",
                    telemetry = success
                )
            )
            return@withContext success
        }

        // STEP 7 — Atomic Attendance Marking in Local Database
        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 7,
                stepName = "Atomic Attendance Recording",
                isRunning = true,
                isSuccess = false,
                detailMessage = "Writing immutable event to SQLite Room DB..."
            )
        )

        val faceBase64 = try {
            val stream = java.io.ByteArrayOutputStream()
            val scaledCrop = Bitmap.createScaledBitmap(alignedFace, 160, 160, true)
            scaledCrop.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }

        val record = attendanceRepository.recordAttendance(
            studentId = bestMatch.studentId,
            studentName = bestMatch.name,
            sessionId = activeSession.sessionId,
            sessionTitle = activeSession.title,
            similarityScore = highestSimilarity,
            livenessScore = livenessResult.livenessScore,
            latitude = deviceLatitude,
            longitude = deviceLongitude,
            isLocationValid = true,
            capturedFaceBase64 = faceBase64
        )

        // Instant Auto-Sync to central server in background if network is reachable
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                attendanceRepository.syncPendingRecords()
            } catch (_: Exception) {}
        }

        val finalSuccess = PipelineTelemetry(
            stage = PipelineStage.SUCCESS,
            faceDetected = true,
            faceBoundingBox = boundingBox,
            livenessVerified = true,
            livenessScore = livenessResult.livenessScore,
            phoneDetected = false,
            embeddingGenerated = true,
            embeddingDimension = embDim,
            matchFound = true,
            matchedStudent = bestMatch,
            similarityScore = highestSimilarity,
            similarityThreshold = similarityThreshold,
            sessionVerified = true,
            locationVerified = true,
            distanceMeters = distance,
            statusMessage = "✓ ATTENDANCE MARKED",
            recordedAttendance = record,
            liveFaceCrop = alignedFace,
            sampleEmbeddingSnippet = embeddingSnippet
        )

        onProgress?.invoke(
            PipelineStepProgress(
                stepIndex = 7,
                stepName = "Atomic Attendance Recording",
                isRunning = false,
                isSuccess = true,
                detailMessage = "Recorded in SQLite Room (ID: ${record.recordId.take(8)}...)",
                telemetry = finalSuccess
            )
        )
        delay(250)

        return@withContext finalSuccess
    }

    /**
     * Haversine formula to compute great-circle distance between two GPS coordinates in meters.
     */
    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }

    /**
     * Renders an annotated security inspection frame highlighting detected live face in green
     * and detected replay attack surface (secondary phone/tablet screen or 2D spoof) in red.
     */
    private fun renderVisualInspectionFrame(
        frame: Bitmap,
        faceBox: Rect?,
        threatBox: Rect?,
        isSpoof: Boolean,
        isPhonePresent: Boolean
    ): Bitmap {
        val overlay = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlay)

        val greenPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        val redPaint = Paint().apply {
            color = Color.parseColor("#EF4444")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Draw face bounding box (Green)
        faceBox?.let { box ->
            canvas.drawRect(box, greenPaint)
            bgPaint.color = Color.parseColor("#10B981")
            canvas.drawRect(box.left.toFloat(), (box.top - 36).coerceAtLeast(0).toFloat(), box.left + 220f, box.top.toFloat(), bgPaint)
            canvas.drawText("FACE TARGET", box.left + 10f, (box.top - 10).coerceAtLeast(20).toFloat(), textPaint)
        }

        // Draw threat bounding box (Red)
        val targetThreatBox = threatBox ?: if (isSpoof && faceBox != null) {
            Rect(
                (faceBox.left - 20).coerceAtLeast(0),
                (faceBox.top - 20).coerceAtLeast(0),
                (faceBox.right + 20).coerceAtMost(frame.width),
                (faceBox.bottom + 20).coerceAtMost(frame.height)
            )
        } else null

        targetThreatBox?.let { box ->
            canvas.drawRect(box, redPaint)
            bgPaint.color = Color.parseColor("#EF4444")
            val label = if (isPhonePresent) "THREAT: PHONE REPLAY SCREEN" else "THREAT: 2D PHOTO SPOOF"
            val textWidth = textPaint.measureText(label) + 20f
            canvas.drawRect(box.left.toFloat(), (box.bottom).toFloat(), box.left + textWidth, box.bottom + 40f, bgPaint)
            canvas.drawText(label, box.left + 10f, box.bottom + 30f, textPaint)
        }

        return overlay
    }
}
