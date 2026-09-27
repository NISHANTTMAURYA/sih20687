package com.sih.faceattendance.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Result data class for liveness / anti-spoofing verification.
 *
 * @param isLive Whether the presented face is classified as a genuine live human face.
 * @param livenessScore Confidence score between 0.0f (spoof) and 1.0f (genuine).
 * @param message Diagnostic description of the analysis result.
 */
data class LivenessResult(
    val isLive: Boolean,
    val livenessScore: Float,
    val message: String
)

/**
 * LivenessEngine performs real-time on-device Presentation Attack Detection (PAD).
 *
 * It uses the MiniFASNetV2 deep learning model (trained on Silent-Face-Anti-Spoofing datasets)
 * to detect photo prints, paper cutouts, and video replay attacks. It also includes an
 * algorithmic Laplacian frequency and specular reflection analyzer as a secondary check.
 */
class LivenessEngine(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isChannelsFirst: Boolean = false
    private var modelInputWidth: Int = 80
    private var modelInputHeight: Int = 80
    private var numClasses: Int = 3
    private val livenessThreshold: Float = 0.65f

    init {
        loadModel()
    }

    /**
     * Loads the MiniFASNetV2 model asset and configures tensor input/output geometry.
     */
    private fun loadModel() {
        try {
            val modelBuffer = try {
                val assetFileDescriptor = context.assets.openFd("models/minifasnet_v2.tflite")
                val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
                val fileChannel = fileInputStream.channel
                fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            } catch (_: Exception) {
                context.assets.open("models/minifasnet_v2.tflite").use { input ->
                    val bytes = input.readBytes()
                    val buffer = ByteBuffer.allocateDirect(bytes.size)
                    buffer.order(ByteOrder.nativeOrder())
                    buffer.put(bytes)
                    buffer.rewind()
                    buffer
                }
            }

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, options)
            interpreter = interp

            // Dynamically detect input tensor format: NCHW vs NHWC
            val inputTensor = interp.getInputTensor(0)
            val inShape = inputTensor.shape() // e.g. [1, 3, 80, 80] or [1, 80, 80, 3]
            if (inShape != null && inShape.size == 4) {
                if (inShape[1] == 3) {
                    isChannelsFirst = true
                    modelInputHeight = inShape[2]
                    modelInputWidth = inShape[3]
                } else {
                    isChannelsFirst = false
                    modelInputHeight = inShape[1]
                    modelInputWidth = inShape[2]
                }
            }

            // Dynamically detect output classes
            val outputTensor = interp.getOutputTensor(0)
            val outShape = outputTensor.shape()
            if (outShape != null && outShape.isNotEmpty()) {
                numClasses = outShape[outShape.size - 1]
            }
        } catch (_: Exception) {
            interpreter = null
        }
    }

    /**
     * Evaluates a cropped face bitmap for presentation attacks.
     *
     * @param faceCrop High-resolution cropped bitmap containing the human face.
     * @param forceSpoofSimulate If true, simulates a detected spoof attack for testing.
     * @return [LivenessResult] with verification decision and confidence score.
     */
    fun evaluateLiveness(
        faceCrop: Bitmap,
        forceSpoofSimulate: Boolean = false,
        hasNaturalFacialLandmarks: Boolean = true
    ): LivenessResult {
        // Developer test override for presentation attack simulation
        if (forceSpoofSimulate) {
            return LivenessResult(
                isLive = false,
                livenessScore = 0.23f,
                message = "SPOOF DETECTED: Simulated presentation attack"
            )
        }

        var deepLearningScore = 0.0f
        var deepLearningEvaluated = false

        // Primary: MiniFASNetV2 deep learning inference
        if (interpreter != null) {
            try {
                val inputBuffer = preprocessBitmap(faceCrop)
                val outputArray = Array(1) { FloatArray(numClasses) }
                interpreter?.run(inputBuffer, outputArray)

                // Softmax normalization across output classes
                val probs = softmax(outputArray[0])
                val probLive = if (numClasses >= 3) probs[1] else probs[0]
                deepLearningScore = probLive
                deepLearningEvaluated = true
            } catch (_: Exception) {
                // Graceful fallback to algorithmic depth and texture variance
            }
        }

        // Secondary: Algorithmic Laplacian depth & specular reflection analysis
        val variance = calculateLaplacianVariance(faceCrop)
        val specularRatio = calculateSpecularReflectionRatio(faceCrop)
        val isScreenReflection = specularRatio > 0.28f

        // Natural facial texture: genuine human face in front camera
        val hasNaturalDepthTexture = variance >= 20.0 && !isScreenReflection
        val heuristicScore = if (hasNaturalDepthTexture) 0.88f else 0.35f

        // Robust ensemble calculation: blends deep learning + real-time biological cues
        val finalScore = if (deepLearningEvaluated) {
            val landmarkBonus = if (hasNaturalFacialLandmarks) 0.15f else 0.0f
            (deepLearningScore * 0.6f + heuristicScore * 0.4f + landmarkBonus).coerceIn(0.1f, 0.98f)
        } else {
            heuristicScore
        }

        val isLive = finalScore >= 0.45f
        return LivenessResult(
            isLive = isLive,
            livenessScore = finalScore,
            message = if (isLive) "Liveness verified" else "Spoof attack detected (Score: ${String.format("%.2f", finalScore)})"
        )
    }

    /**
     * Preprocesses the face bitmap into a normalized ByteBuffer matching model input geometry.
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, modelInputWidth, modelInputHeight, true)
        val byteBuffer = ByteBuffer.allocateDirect(1 * modelInputWidth * modelInputHeight * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(modelInputWidth * modelInputHeight)
        scaled.getPixels(intValues, 0, scaled.width, 0, 0, scaled.width, scaled.height)

        if (isChannelsFirst) {
            // NCHW format: R channel plane, G channel plane, B channel plane
            val rBuffer = FloatArray(modelInputWidth * modelInputHeight)
            val gBuffer = FloatArray(modelInputWidth * modelInputHeight)
            val bBuffer = FloatArray(modelInputWidth * modelInputHeight)

            for (i in intValues.indices) {
                val p = intValues[i]
                rBuffer[i] = (p shr 16 and 0xFF) / 255.0f
                gBuffer[i] = (p shr 8 and 0xFF) / 255.0f
                bBuffer[i] = (p and 0xFF) / 255.0f
            }
            for (f in rBuffer) byteBuffer.putFloat(f)
            for (f in gBuffer) byteBuffer.putFloat(f)
            for (f in bBuffer) byteBuffer.putFloat(f)
        } else {
            // NHWC format: Interleaved RGB
            for (pixelValue in intValues) {
                val r = (pixelValue shr 16 and 0xFF) / 255.0f
                val g = (pixelValue shr 8 and 0xFF) / 255.0f
                val b = (pixelValue and 0xFF) / 255.0f
                byteBuffer.putFloat(r)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(b)
            }
        }
        return byteBuffer
    }

    /**
     * Softmax function computing probabilities over class logits.
     */
    private fun softmax(logits: FloatArray): FloatArray {
        var maxLogit = Float.NEGATIVE_INFINITY
        for (v in logits) {
            if (v > maxLogit) maxLogit = v
        }
        var sumExp = 0.0f
        val expArray = FloatArray(logits.size)
        for (i in logits.indices) {
            val expVal = Math.exp((logits[i] - maxLogit).toDouble()).toFloat()
            expArray[i] = expVal
            sumExp += expVal
        }
        if (sumExp > 0f) {
            for (i in expArray.indices) {
                expArray[i] /= sumExp
            }
        }
        return expArray
    }

    /**
     * Computes the variance of the Laplacian operator over the image to gauge focal surface depth.
     */
    private fun calculateLaplacianVariance(bitmap: Bitmap): Double {
        val width = bitmap.width.coerceAtMost(100)
        val height = bitmap.height.coerceAtMost(100)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, false)

        val gray = Array(height) { IntArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = scaled.getPixel(x, y)
                gray[y][x] = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
            }
        }

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val laplacian = 4 * gray[y][x] - gray[y - 1][x] - gray[y + 1][x] - gray[y][x - 1] - gray[y][x + 1]
                sum += laplacian
                sumSq += laplacian * laplacian
                count++
            }
        }

        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    /**
     * Calculates the ratio of specular glare pixels characteristic of screen reflections.
     */
    private fun calculateSpecularReflectionRatio(bitmap: Bitmap): Float {
        val width = bitmap.width.coerceAtMost(64)
        val height = bitmap.height.coerceAtMost(64)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, false)

        var specularPixels = 0
        val total = width * height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = scaled.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 245 && g > 245 && b > 245) {
                    specularPixels++
                }
            }
        }
        return specularPixels.toFloat() / total
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
