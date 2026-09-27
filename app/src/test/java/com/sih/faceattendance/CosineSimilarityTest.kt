package com.sih.faceattendance

import com.sih.faceattendance.ml.CosineSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CosineSimilarityTest {

    @Test
    fun testIdenticalVectorsHaveSimilarityOne() {
        val v1 = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val v2 = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val similarity = CosineSimilarity.compute(v1, v2)
        assertEquals(1.0f, similarity, 1e-4f)
    }

    @Test
    fun testOrthogonalVectorsHaveSimilarityZero() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(0.0f, 1.0f, 0.0f, 0.0f)
        val similarity = CosineSimilarity.compute(v1, v2)
        assertEquals(0.0f, similarity, 1e-4f)
    }

    @Test
    fun testOppositeVectorsHaveSimilarityMinusOne() {
        val v1 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val v2 = floatArrayOf(-1.0f, 0.0f, 0.0f)
        val similarity = CosineSimilarity.compute(v1, v2)
        assertEquals(-1.0f, similarity, 1e-4f)
    }

    @Test
    fun testL2NormalizationProducesUnitLength() {
        val vector = floatArrayOf(3.0f, 4.0f)
        val normalized = CosineSimilarity.l2Normalize(vector)

        var sumSq = 0.0f
        for (v in normalized) {
            sumSq += v * v
        }
        val length = sqrt(sumSq)
        assertEquals(1.0f, length, 1e-4f)
        assertEquals(0.6f, normalized[0], 1e-4f)
        assertEquals(0.8f, normalized[1], 1e-4f)
    }

    @Test
    fun testCosineSimilarityThresholdMatching() {
        val enrolled = CosineSimilarity.l2Normalize(FloatArray(128) { 0.1f })
        val liveScan = CosineSimilarity.l2Normalize(FloatArray(128) { if (it % 2 == 0) 0.1f else 0.095f })

        val similarity = CosineSimilarity.compute(enrolled, liveScan)
        assertTrue("Similarity should be high for subtle noise", similarity >= 0.90f)
        assertTrue("Similarity should pass 0.70 threshold", similarity >= 0.70f)
    }
}
