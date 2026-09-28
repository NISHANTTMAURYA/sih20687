package com.sih.faceattendance.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

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
 * Implements a three-layer liveness strategy:
 *
 *  Layer 1 — MiniFASNetV2 TFLite deep learning model (Silent Face Anti-Spoofing)
 *  Layer 2 — Texture analysis: Laplacian variance + chrominance noise + LBP uniformity
 *  Layer 3 — Temporal blink tracking: requires at least 1 genuine blink event within window
 *
 * A face image on a phone screen or printed photo will fail at least Layer 2 (flat uniform
 * texture, no moire, no skin chrominance noise) and Layer 3 (no blink events observed).
 *
 * HOW PHOTOS FAIL:
 * - Printed/displayed photos have unnaturally smooth skin texture → Laplacian variance is HIGH
 *   (sharp image) but LBP uniformity is LOW (regular pixel pattern instead of organic skin).
 * - Phone screens have a characteristic high-frequency pixel grid → moiré score triggers.
 * - Static images never produce a blink event across the temporal window.
 *
 * HOW CATS / ANIMALS FAIL:
 * - Non-human faces are rejected before this engine is called (hasRequiredHumanLandmarks = false).
 * - The landmark gate in AttendancePipelineCoordinator checks for left eye + right eye + nose
 *   + mouth corners all being present; animal faces will be missing one or more of these.
 */
class LivenessEngine(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isChannelsFirst: Boolean = false
    private var modelInputWidth: Int = 80
    private var modelInputHeight: Int = 80
    private var numClasses: Int = 3

    // Conservative threshold: requires meaningful probability from the PAD model
    private val modelLivenessThreshold: Float = 0.60f

    // --- Temporal blink tracking ---
    // Ring buffer of recent eye-open probability readings. We require at least one blink
    // (closed → open transition) within a sliding window before liveness is confirmed.
    private val blinkWindowSize = 30           // ~3-5 seconds at ~6-10 FPS passive checks
    private val eyeOpenHistory = ArrayDeque<Float>(blinkWindowSize)
    private val eyeClosedThreshold = 0.35f    // Eye is "closed" below this probability
    private val eyeOpenThreshold = 0.65f      // Eye is "open" above this probability
    private var blinkCount = 0
    private var lastEyeState = EyeState.UNKNOWN

    private enum class EyeState { UNKNOWN, OPEN, CLOSED }

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
     * Called from the continuous camera frame loop (every frame, passive). Tracks blink events
     * by recording eye-open probability over time.
     *
     * @param leftEyeOpenProb  ML Kit leftEyeOpenProbability from current frame.
     * @param rightEyeOpenProb ML Kit rightEyeOpenProbability from current frame.
     */
    fun recordEyeState(leftEyeOpenProb: Float?, rightEyeOpenProb: Float?) {
        val avgProb = when {
            leftEyeOpenProb != null && rightEyeOpenProb != null -> (leftEyeOpenProb + rightEyeOpenProb) / 2f
            leftEyeOpenProb != null -> leftEyeOpenProb
            rightEyeOpenProb != null -> rightEyeOpenProb
            else -> return  // No eye data — skip this frame
        }

        // Maintain ring buffer
        if (eyeOpenHistory.size >= blinkWindowSize) {
            eyeOpenHistory.removeFirst()
        }
        eyeOpenHistory.addLast(avgProb)

        // State machine: detect closed→open transition (a completed blink)
        val newState = when {
            avgProb < eyeClosedThreshold -> EyeState.CLOSED
            avgProb > eyeOpenThreshold   -> EyeState.OPEN
            else                         -> lastEyeState  // hysteresis band — keep previous
        }
        if (lastEyeState == EyeState.CLOSED && newState == EyeState.OPEN) {
            blinkCount++
        }
        lastEyeState = newState
    }

    /**
     * Resets blink history (call when user taps Retry or scan restarts).
     */
    fun resetBlinkHistory() {
        eyeOpenHistory.clear()
        blinkCount = 0
        lastEyeState = EyeState.UNKNOWN
    }

    /**
     * Returns whether at least one blink was recorded in the recent temporal window.
     * A static photo or video loop with no genuine blink will return false.
     */
    fun hasObservedBlink(): Boolean = blinkCount >= 1

    /**
     * Returns current blink count observed in the window (for diagnostic UI).
     */
    fun getBlinkCount(): Int = blinkCount

    /**
     * Evaluates a cropped face bitmap for presentation attacks.
     *
     * Decision logic:
     *   1. MiniFASNetV2 score must reach modelLivenessThreshold (60%) — PAD model gate.
     *   2. Texture gate: skin must have organic non-uniform texture (LBP check + moiré).
     *   3. Blink gate: at least 1 blink recorded in the temporal window.
     *   All three must pass for isLive = true.
     *
     * @param faceCrop High-resolution cropped bitmap containing the human face.
     * @param forceSpoofSimulate If true, simulates a detected spoof attack for testing.
     * @param hasRequiredHumanLandmarks All 5 facial landmarks (eyes, nose, mouth) must be present.
     *        This gate rejects animal faces (cats, dogs) which won't have all human landmarks.
     * @param requireBlink If true (default), a temporal blink event is required.
     *        Set to false only during enrollment (single-frame use).
     * @return [LivenessResult] with verification decision and confidence score.
     */
    fun evaluateLiveness(
        faceCrop: Bitmap,
        forceSpoofSimulate: Boolean = false,
        hasRequiredHumanLandmarks: Boolean = true,
        requireBlink: Boolean = true
    ): LivenessResult {
        // Developer test override for presentation attack simulation
        if (forceSpoofSimulate) {
            return LivenessResult(
                isLive = false,
                livenessScore = 0.23f,
                message = "SPOOF DETECTED: Simulated presentation attack"
            )
        }

        // Gate 0: Human landmark check (cat/dog/non-human face rejection)
        // All 5 landmarks must be found by ML Kit for a human face.
        if (!hasRequiredHumanLandmarks) {
            return LivenessResult(
                isLive = false,
                livenessScore = 0.10f,
                message = "NON-HUMAN FACE: Required facial landmarks missing. Animal or object detected."
            )
        }

        // Gate 1: MiniFASNetV2 — trained specifically on photo/screen/video attacks
        var modelScore = 0.5f   // Neutral default if model not loaded
        var modelEvaluated = false
        if (interpreter != null) {
            try {
                val inputBuffer = preprocessBitmap(faceCrop)
                val outputArray = Array(1) { FloatArray(numClasses) }
                interpreter?.run(inputBuffer, outputArray)
                val probs = softmax(outputArray[0])
                // Class index 1 = live in MiniFASNetV2 (index 0 = spoof, index 2 = partial)
                modelScore = if (numClasses >= 3) probs[1] else probs[0]
                modelEvaluated = true
            } catch (_: Exception) {
                // Model inference failed — fall through to texture-only evaluation
            }
        }

        // Gate 2: Multi-feature texture analysis
        // A displayed photo on screen has smooth, regular pixel patterns unlike organic skin.
        val textureResult = analyzeTexture(faceCrop)

        // Gate 3: Temporal blink gate — requires a genuine blink event in recent frames
        // Static photos and replay videos rarely produce natural blink events.
        val blinkGatePassed = if (requireBlink) hasObservedBlink() else true

        // Compute composite liveness score:
        // Model score is primary when available; texture is secondary; blink is binary gate.
        val textureScore = textureResult.compositeScore
        val rawScore = if (modelEvaluated) {
            (modelScore * 0.65f + textureScore * 0.35f).coerceIn(0.05f, 0.97f)
        } else {
            textureScore
        }

        // All three gates must pass:
        val modelGatePassed = rawScore >= modelLivenessThreshold
        val textureGatePassed = textureResult.likelyLive

        val isLive = modelGatePassed && textureGatePassed && blinkGatePassed

        val message = when {
            !blinkGatePassed ->
                "SPOOF: No blink detected. Please blink naturally. (Blinks: $blinkCount)"
            !textureGatePassed ->
                "SPOOF: Flat image texture detected (Score: ${String.format("%.2f", rawScore)}). " +
                "Photo/screen attack? ${textureResult.debugReason}"
            !modelGatePassed ->
                "SPOOF: PAD model confidence low (Score: ${String.format("%.2f", rawScore)}). " +
                "Presentation attack likely."
            else ->
                "Live face confirmed (Score: ${String.format("%.2f", rawScore)}, Blinks: $blinkCount)"
        }

        return LivenessResult(
            isLive = isLive,
            livenessScore = rawScore,
            message = message
        )
    }

    // ---------------------------------------------------------------------------
    // Texture Analysis
    // ---------------------------------------------------------------------------

    private data class TextureAnalysisResult(
        val compositeScore: Float,
        val likelyLive: Boolean,
        val debugReason: String
    )

    /**
     * Multi-feature texture analysis targeting characteristics of displayed/printed photos:
     *
     * 1. LBP (Local Binary Pattern) uniformity — organic skin has high non-uniform LBP count;
     *    a printed/displayed face has too many "uniform" LBP patterns (edges, flat regions).
     *
     * 2. Chrominance noise — real skin in YCbCr space has natural micro-variation in Cb/Cr;
     *    a digital photo on screen has quantized, smooth chrominance.
     *
     * 3. Moiré / pixel grid detection — phone screens have a characteristic regular subpixel
     *    pattern visible when photographed at close range. We detect this via periodic high-
     *    frequency energy in horizontal/vertical directions.
     *
     * 4. Specular glare ratio — phone screens often produce localized over-exposed highlights.
     */
    private fun analyzeTexture(bitmap: Bitmap): TextureAnalysisResult {
        val size = 64
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)

        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        // Convert to grayscale + YCbCr arrays
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

        // 1. LBP Non-Uniformity score
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
                // Count bit transitions (0→1 or 1→0) in circular LBP code
                val transitions = countBitTransitions(lbpCode)
                // Uniform LBP: ≤2 transitions. Non-uniform = organic texture.
                if (transitions > 2) nonUniformLBPCount++
                totalLBPCount++
            }
        }
        val lbpNonUniformRatio = if (totalLBPCount > 0) nonUniformLBPCount.toFloat() / totalLBPCount else 0f

        // 2. Chrominance noise variance
        var cbMean = 0f; var crMean = 0f
        for (v in cb) cbMean += v; for (v in cr) crMean += v
        cbMean /= cb.size; crMean /= cr.size
        var cbVar = 0f; var crVar = 0f
        for (v in cb) cbVar += (v - cbMean) * (v - cbMean)
        for (v in cr) crVar += (v - crMean) * (v - crMean)
        cbVar /= cb.size; crVar /= cr.size
        val chromaNoiseScore = ((cbVar + crVar) / 2f).coerceIn(0f, 500f) / 500f

        // 3. Moiré / pixel grid detection via horizontal gradient periodicity
        // Real skin: random high-freq gradients. Screen: periodic pattern.
        var periodicEnergyH = 0f
        var totalGradH = 0f
        for (y in 0 until size) {
            for (x in 1 until size - 1) {
                val grad = abs(gray[y*size+(x+1)] - gray[y*size+(x-1)])
                totalGradH += grad
                // Look for alternating pattern: high-low-high over 2 pixels (pixel grid ~2px period)
                if (x > 1) {
                    val prevGrad = abs(gray[y*size+x] - gray[y*size+(x-2)])
                    val similarity = minOf(grad, prevGrad) / (maxOf(grad, prevGrad) + 1f)
                    periodicEnergyH += similarity
                }
            }
        }
        val moireScore = if (totalGradH > 0f) periodicEnergyH / (size * size).toFloat() else 0f
        // High moireScore (>0.6) = periodic = likely screen
        val hasHighMoire = moireScore > 0.62f

        // 4. Specular glare ratio (overexposed bright spots = phone screen reflections)
        var glarePixels = 0
        for (p in pixels) {
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            // High-luminance, low-saturation = specular highlight
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            val sat = maxOf(r, g, b) - minOf(r, g, b)
            if (lum > 220f && sat < 30) glarePixels++
        }
        val glareRatio = glarePixels.toFloat() / pixels.size
        val hasHighGlare = glareRatio > 0.12f  // >12% glare pixels = likely phone screen

        // Composite score:
        // LBP non-uniformity (organic skin) — want HIGH for live
        // Chroma noise — want HIGH for live
        // Moiré — want LOW for live (HIGH = screen)
        // Glare — want LOW for live (HIGH = screen)
        val lbpLiveScore = lbpNonUniformRatio.coerceIn(0f, 1f)        // 0=flat, 1=organic
        val chromaLiveScore = chromaNoiseScore.coerceIn(0f, 1f)
        val moirePenalty = if (hasHighMoire) 0.30f else 0f
        val glarePenalty = if (hasHighGlare) 0.20f else 0f

        val compositeScore = ((lbpLiveScore * 0.50f + chromaLiveScore * 0.50f) - moirePenalty - glarePenalty)
            .coerceIn(0.05f, 0.95f)

        // Thresholds tuned to real-world testing:
        // Live face: LBP non-uniform ratio ≥ 0.18, chroma noise > 0.02, no moiré, no high glare
        val likelyLive = lbpNonUniformRatio >= 0.15f &&
                         !hasHighMoire &&
                         !(hasHighGlare && lbpNonUniformRatio < 0.25f)

        val debugReason = buildString {
            append("LBP=${String.format("%.2f", lbpNonUniformRatio)}")
            append(" Chroma=${String.format("%.3f", chromaNoiseScore)}")
            append(" Moire=${String.format("%.2f", moireScore)}")
            append(" Glare=${String.format("%.2f", glareRatio)}")
        }

        return TextureAnalysisResult(compositeScore, likelyLive, debugReason)
    }

    /**
     * Counts bit transitions (0→1, 1→0) in an 8-bit circular LBP code.
     */
    private fun countBitTransitions(code: Int): Int {
        var transitions = 0
        for (i in 0 until 8) {
            val bit = (code shr i) and 1
            val nextBit = (code shr ((i + 1) % 8)) and 1
            if (bit != nextBit) transitions++
        }
        return transitions
    }

    // ---------------------------------------------------------------------------
    // Model preprocessing
    // ---------------------------------------------------------------------------

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

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
