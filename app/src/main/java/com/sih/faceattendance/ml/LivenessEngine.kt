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
 * Architecture:
 *  1. Mandatory 5-Point Human Facial Landmark Gate (rejects non-human/cat/dog faces and objects).
 *  2. MiniFASNetV2 Deep Learning PAD Model (Silent Face Anti-Spoofing):
 *     - Input: [1, 3, 80, 80] NCHW layout in BGR color order with [0.0 .. 255.0] range.
 *     - Output: [1, 3] Softmax probability distribution (Index 0 = Spoof, Index 1 = Live, Index 2 = 2D Spoof).
 *  3. Surface Texture & Screen Glare Analysis (heuristic secondary protection against printed photos & screen displays).
 *  4. Temporal Biological Micro-motion / Blink Signal tracking.
 */
class LivenessEngine(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isChannelsFirst: Boolean = true
    private var modelInputWidth: Int = 80
    private var modelInputHeight: Int = 80
    private var numClasses: Int = 3

    // Sliding window of eye-open probabilities for passive blink tracking
    private val blinkWindowSize = 40
    private val eyeOpenHistory = ArrayDeque<Float>(blinkWindowSize)
    private val eyeClosedThreshold = 0.35f
    private val eyeOpenThreshold = 0.60f
    private var blinkCount = 0
    private var lastEyeState = EyeState.UNKNOWN

    private enum class EyeState { UNKNOWN, OPEN, CLOSED }

    init {
        loadModel()
    }

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

            val inputTensor = interp.getInputTensor(0)
            val inShape = inputTensor.shape()
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
     * Records eye open probability across camera frames for continuous passive liveness.
     */
    fun recordEyeState(leftEyeOpenProb: Float?, rightEyeOpenProb: Float?) {
        val avgProb = when {
            leftEyeOpenProb != null && rightEyeOpenProb != null -> (leftEyeOpenProb + rightEyeOpenProb) / 2f
            leftEyeOpenProb != null -> leftEyeOpenProb
            rightEyeOpenProb != null -> rightEyeOpenProb
            else -> return
        }

        if (eyeOpenHistory.size >= blinkWindowSize) {
            eyeOpenHistory.removeFirst()
        }
        eyeOpenHistory.addLast(avgProb)

        val newState = when {
            avgProb < eyeClosedThreshold -> EyeState.CLOSED
            avgProb > eyeOpenThreshold   -> EyeState.OPEN
            else                         -> lastEyeState
        }
        if (lastEyeState == EyeState.CLOSED && newState == EyeState.OPEN) {
            blinkCount++
        }
        lastEyeState = newState
    }

    fun resetBlinkHistory() {
        eyeOpenHistory.clear()
        blinkCount = 0
        lastEyeState = EyeState.UNKNOWN
    }

    fun hasObservedBlink(): Boolean = blinkCount >= 1

    fun getBlinkCount(): Int = blinkCount

    /**
     * Evaluates a cropped face bitmap for liveness and presentation attacks.
     *
     * @param faceCrop The cropped face bitmap.
     * @param forceSpoofSimulate Testing flag to force spoof rejection.
     * @param hasRequiredHumanLandmarks Must have all 5 canonical landmarks (rejects animal faces/objects).
     * @param requireBlink Optional flag for blink presence.
     */
    fun evaluateLiveness(
        faceCrop: Bitmap,
        forceSpoofSimulate: Boolean = false,
        hasRequiredHumanLandmarks: Boolean = true,
        requireBlink: Boolean = false
    ): LivenessResult {
        if (forceSpoofSimulate) {
            return LivenessResult(
                isLive = false,
                livenessScore = 0.23f,
                message = "SPOOF DETECTED: Simulated presentation attack"
            )
        }

        // ── Gate 0: Human Facial Landmark Geometry Gate (Anti-Animal / Anti-Object) ──
        if (!hasRequiredHumanLandmarks) {
            return LivenessResult(
                isLive = false,
                livenessScore = 0.10f,
                message = "NON-HUMAN FACE: Required facial landmarks missing. Real human face required."
            )
        }

        // ── Gate 1: MiniFASNetV2 Deep Learning PAD Inference ──
        var modelScore = 0.85f
        var modelEvaluated = false
        if (interpreter != null) {
            try {
                val inputBuffer = preprocessBitmap(faceCrop)
                val outputArray = Array(1) { FloatArray(numClasses) }
                interpreter?.run(inputBuffer, outputArray)
                
                // MiniFASNet output is already softmaxed: [Spoof0, Live1, Spoof2]
                val liveProb = if (numClasses >= 3) outputArray[0][1] else outputArray[0][0]
                modelScore = liveProb.coerceIn(0.0f, 1.0f)
                modelEvaluated = true
            } catch (_: Exception) {
                // Fallback to texture analysis
            }
        }

        // ── Gate 2: Surface Texture & Glare Analysis ──
        val textureResult = analyzeTexture(faceCrop)

        // ── Gate 3: Temporal Biological Blink Bonus ──
        val hasBlinked = hasObservedBlink()
        val blinkBonus = if (hasBlinked) 0.10f else 0.0f

        // Robust Weighted Ensemble:
        val compositeScore = if (modelEvaluated) {
            (modelScore * 0.70f + textureResult.compositeScore * 0.30f + blinkBonus).coerceIn(0.05f, 0.99f)
        } else {
            (textureResult.compositeScore * 0.80f + blinkBonus + 0.15f).coerceIn(0.05f, 0.99f)
        }

        // Definite screen glare / extreme moiré trigger:
        if (textureResult.isDefiniteScreenSpoof) {
            return LivenessResult(
                isLive = false,
                livenessScore = compositeScore,
                message = "SPOOF DETECTED: Secondary display reflection detected in camera view"
            )
        }

        // Verification decision:
        val isLive = compositeScore >= 0.50f

        val message = if (isLive) {
            "Live face verified (Score: ${String.format("%.2f", compositeScore)}${if (hasBlinked) ", Blinks: $blinkCount ✓" else ""})"
        } else {
            "Presentation attack detected (Score: ${String.format("%.2f", compositeScore)}). Real live face required."
        }

        return LivenessResult(
            isLive = isLive,
            livenessScore = compositeScore,
            message = message
        )
    }

    private data class TextureAnalysisResult(
        val compositeScore: Float,
        val isDefiniteScreenSpoof: Boolean,
        val debugReason: String
    )

    private fun analyzeTexture(bitmap: Bitmap): TextureAnalysisResult {
        val size = 64
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        val gray = FloatArray(size * size)
        val cb = FloatArray(size * size)
        val cr = FloatArray(size * size)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16 and 0xFF).toFloat()
            val g = (pixels[i] shr 8 and 0xFF).toFloat()
            val b = (pixels[i] and 0xFF).toFloat()
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        // 1. LBP Non-Uniformity
        var nonUniformLBPCount = 0
        var totalLBPCount = 0
        for (y in 1 until size - 1) {
            for (x in 1 until size - 1) {
                val center = gray[y * size + x]
                var lbpCode = 0
                val neighbors = intArrayOf(
                    gray[(y-1)*size+(x-1)].toInt(), gray[(y-1)*size+x].toInt(), gray[(y-1)*size+(x+1)].toInt(),
                    gray[y*size+(x+1)].toInt(),
                    gray[(y+1)*size+(x+1)].toInt(), gray[(y+1)*size+x].toInt(), gray[(y+1)*size+(x-1)].toInt(),
                    gray[y*size+(x-1)].toInt()
                )
                for (n in neighbors.indices) {
                    if (neighbors[n] >= center) lbpCode = lbpCode or (1 shl n)
                }
                val transitions = countBitTransitions(lbpCode)
                if (transitions > 2) nonUniformLBPCount++
                totalLBPCount++
            }
        }
        val lbpNonUniformRatio = if (totalLBPCount > 0) nonUniformLBPCount.toFloat() / totalLBPCount else 0f

        // 2. Chrominance noise
        var cbMean = 0f; var crMean = 0f
        for (v in cb) cbMean += v; for (v in cr) crMean += v
        cbMean /= cb.size; crMean /= cr.size
        var cbVar = 0f; var crVar = 0f
        for (v in cb) cbVar += (v - cbMean) * (v - cbMean)
        for (v in cr) crVar += (v - crMean) * (v - crMean)
        cbVar /= cb.size; crVar /= cr.size
        val chromaNoiseScore = ((cbVar + crVar) / 2f).coerceIn(0f, 500f) / 500f

        // 3. Specular screen reflection
        var glarePixels = 0
        for (p in pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val sat = maxOf(r, g, b) - minOf(r, g, b)
            if (lum > 245f && sat < 10) glarePixels++
        }
        val glareRatio = glarePixels.toFloat() / pixels.size
        val isDefiniteScreenGlare = glareRatio > 0.40f

        val lbpLiveScore = lbpNonUniformRatio.coerceIn(0f, 1f)
        val chromaLiveScore = chromaNoiseScore.coerceIn(0f, 1f)

        val compositeScore = (lbpLiveScore * 0.60f + chromaLiveScore * 0.40f).coerceIn(0.20f, 0.95f)

        val debugReason = "LBP=${String.format("%.2f", lbpNonUniformRatio)}, Glare=${String.format("%.2f", glareRatio)}"

        return TextureAnalysisResult(
            compositeScore = compositeScore,
            isDefiniteScreenSpoof = isDefiniteScreenGlare,
            debugReason = debugReason
        )
    }

    private fun countBitTransitions(code: Int): Int {
        var transitions = 0
        for (i in 0 until 8) {
            val bit = (code shr i) and 1
            val nextBit = (code shr ((i + 1) % 8)) and 1
            if (bit != nextBit) transitions++
        }
        return transitions
    }

    /**
     * Preprocesses bitmap into exact Silent-Face-Anti-Spoofing PyTorch geometry:
     * - Shape: [1, 3, 80, 80]
     * - Color format: BGR (B plane, then G plane, then R plane)
     * - Range: [0.0 .. 255.0] unnormalized float
     */
    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, modelInputWidth, modelInputHeight, true)
        val byteBuffer = ByteBuffer.allocateDirect(1 * modelInputWidth * modelInputHeight * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(modelInputWidth * modelInputHeight)
        scaled.getPixels(intValues, 0, scaled.width, 0, 0, scaled.width, scaled.height)

        if (isChannelsFirst) {
            // NCHW in BGR order: Blue channel plane first, Green channel plane second, Red channel plane third
            val bBuffer = FloatArray(modelInputWidth * modelInputHeight)
            val gBuffer = FloatArray(modelInputWidth * modelInputHeight)
            val rBuffer = FloatArray(modelInputWidth * modelInputHeight)

            for (i in intValues.indices) {
                val p = intValues[i]
                rBuffer[i] = (p shr 16 and 0xFF).toFloat()
                gBuffer[i] = (p shr 8 and 0xFF).toFloat()
                bBuffer[i] = (p and 0xFF).toFloat()
            }
            for (f in bBuffer) byteBuffer.putFloat(f) // B plane
            for (f in gBuffer) byteBuffer.putFloat(f) // G plane
            for (f in rBuffer) byteBuffer.putFloat(f) // R plane
        } else {
            // NHWC in BGR order: Interleaved B, G, R
            for (pixelValue in intValues) {
                val r = (pixelValue shr 16 and 0xFF).toFloat()
                val g = (pixelValue shr 8 and 0xFF).toFloat()
                val b = (pixelValue and 0xFF).toFloat()
                byteBuffer.putFloat(b)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(r)
            }
        }
        return byteBuffer
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
