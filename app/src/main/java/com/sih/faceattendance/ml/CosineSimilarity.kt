package com.sih.faceattendance.ml

import kotlin.math.sqrt

object CosineSimilarity {

    /**
     * Computes the cosine similarity between two float vectors.
     * Cosine Similarity = (A . B) / (||A|| * ||B||)
     * Values range from -1.0 to 1.0, where 1.0 means identical direction.
     */
    fun compute(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty()) {
            return 0.0f
        }

        val compareLength = minOf(v1.size, v2.size)
        if (compareLength == 0) return 0.0f

        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        for (i in 0 until compareLength) {
            val a = v1[i]
            val b = v2[i]
            dotProduct += a * b
            normA += a * a
            normB += b * b
        }

        val denom = sqrt(normA) * sqrt(normB)
        if (denom == 0.0f) return 0.0f

        val similarity = dotProduct / denom
        // Clamp to [-1.0, 1.0] to prevent floating point inaccuracies
        return similarity.coerceIn(-1.0f, 1.0f)
    }

    /**
     * L2 normalizes a float vector in place so that its Euclidean norm is 1.0.
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)
        if (norm > 1e-6f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }
}
