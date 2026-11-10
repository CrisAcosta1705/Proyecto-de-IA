package com.proyectoia.asistencia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedEmbeddingMatcherTest {
    @Test
    fun acceptsConsistentReferences() {
        val matcher = UnifiedEmbeddingMatcher(
            listOf(
                reference("001", floatArrayOf(1f, 0f, 0f)),
                reference("001", floatArrayOf(0.95f, 0.31f, 0f))
            )
        )

        val plan = matcher.plan(listOf(floatArrayOf(1f, 0f, 0f)))

        assertEquals("001", plan.acceptedBySample.single()?.studentId)
    }

    @Test
    fun rejectsReferencesWithoutSecondReferenceSupport() {
        val matcher = UnifiedEmbeddingMatcher(
            listOf(
                reference("001", floatArrayOf(1f, 0f, 0f)),
                reference("001", floatArrayOf(0f, 1f, 0f))
            )
        )

        val plan = matcher.plan(listOf(floatArrayOf(1f, 0f, 0f)))

        assertNull(plan.acceptedBySample.single())
    }

    @Test
    fun assignsOneStudentToOnlyOneFace() {
        val matcher = UnifiedEmbeddingMatcher(
            listOf(reference("001", floatArrayOf(1f, 0f, 0f)))
        )

        val plan = matcher.plan(
            listOf(
                floatArrayOf(1f, 0f, 0f),
                floatArrayOf(0.99f, 0.01f, 0f)
            )
        )

        assertEquals("001", plan.acceptedBySample[0]?.studentId)
        assertNull(plan.acceptedBySample[1])
    }

    @Test
    fun rejectsAmbiguousIdentity() {
        val matcher = UnifiedEmbeddingMatcher(
            listOf(
                reference("001", floatArrayOf(1f, 0f)),
                reference("002", floatArrayOf(0f, 1f))
            )
        )

        val plan = matcher.plan(listOf(floatArrayOf(1f, 1f)))

        assertNull(plan.acceptedBySample.single())
        assertTrue(plan.rankedBySample.single().isNotEmpty())
        assertFalse(plan.acceptedBySample.any { it != null })
    }

    private fun reference(studentId: String, embedding: FloatArray) =
        RecognitionReference(studentId, "Estudiante $studentId", embedding)
}
