package com.sih.faceattendance.ml

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DetectedFaceResult(
    val boundingBox: Rect,
    val headEulerAngleX: Float,
    val headEulerAngleY: Float,
    val headEulerAngleZ: Float,
    val smilingProbability: Float?,
    val leftEyeOpenProbability: Float?,
    val rightEyeOpenProbability: Float?,
    val leftEyePosition: PointF? = null,
    val rightEyePosition: PointF? = null,
    val noseBasePosition: PointF? = null,
    val mouthLeftPosition: PointF? = null,
    val mouthRightPosition: PointF? = null
)

class FaceDetectorEngine {

    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.12f)
        .enableTracking()
        .build()

    private val detector = FaceDetection.getClient(detectorOptions)

    suspend fun detectFaces(bitmap: Bitmap, rotationDegrees: Int = 0): List<DetectedFaceResult> {
        val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
        return detectFaces(inputImage)
    }

    suspend fun detectFaces(inputImage: InputImage): List<DetectedFaceResult> {
        return suspendCancellableCoroutine { continuation ->
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    val results = faces.map { face ->
                        DetectedFaceResult(
                            boundingBox = face.boundingBox,
                            headEulerAngleX = face.headEulerAngleX,
                            headEulerAngleY = face.headEulerAngleY,
                            headEulerAngleZ = face.headEulerAngleZ,
                            smilingProbability = face.smilingProbability,
                            leftEyeOpenProbability = face.leftEyeOpenProbability,
                            rightEyeOpenProbability = face.rightEyeOpenProbability,
                            leftEyePosition = face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                            rightEyePosition = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
                            noseBasePosition = face.getLandmark(FaceLandmark.NOSE_BASE)?.position,
                            mouthLeftPosition = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position,
                            mouthRightPosition = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
                        )
                    }
                    continuation.resume(results)
                }
                .addOnFailureListener {
                    continuation.resume(emptyList())
                }
        }
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Exception) {}
    }
}
