package com.proyectoia.asistencia

import kotlin.math.sqrt

object RecognitionPolicy {
    const val PROFILE_ID = "arcface_w600k_mbf_112x112_v3"
    const val MODEL_FILE = "w600k_mbf.onnx"
    const val MODEL_SHA256 =
        "9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f"
    const val INPUT_SIZE = 112
    const val EMBEDDING_SIZE = 512
    const val SIMILARITY_THRESHOLD = 0.50f
    const val SINGLE_REFERENCE_THRESHOLD = 0.58f
    const val MINIMUM_SCORE_MARGIN = 0.05f
    const val MINIMUM_REFERENCE_SUPPORT = 0.38f
    const val SECOND_REFERENCE_WEIGHT = 0.30f
}

data class RecognitionReference(
    val studentId: String,
    val studentName: String,
    val embedding: FloatArray
)

data class RecognitionIdentityScore(
    val studentId: String,
    val studentName: String,
    val similarity: Float,
    val referenceCount: Int,
    val hasReferenceSupport: Boolean
)

data class UnifiedRecognitionPlan(
    val rankedBySample: List<List<RecognitionIdentityScore>>,
    val acceptedBySample: List<RecognitionIdentityScore?>
)

class UnifiedEmbeddingMatcher(references: List<RecognitionReference>) {
    private val referencesByStudent = references
        .groupBy { it.studentId }
        .mapValues { (_, items) ->
            items.map { item -> item.copy(embedding = normalized(item.embedding)) }
        }

    fun plan(queries: List<FloatArray>): UnifiedRecognitionPlan {
        val rankedBySample = queries.map(::rankedScores)
        val candidates = rankedBySample.mapIndexedNotNull { sampleIndex, scores ->
            acceptedScore(scores)?.let { Triple(it.similarity, sampleIndex, it) }
        }.sortedWith(
            compareByDescending<Triple<Float, Int, RecognitionIdentityScore>> { it.first }
                .thenBy { it.second }
                .thenBy { it.third.studentId }
        )
        val claimedStudents = mutableSetOf<String>()
        val acceptedBySample = MutableList<RecognitionIdentityScore?>(queries.size) { null }
        candidates.forEach { (_, sampleIndex, score) ->
            if (claimedStudents.add(score.studentId)) acceptedBySample[sampleIndex] = score
        }
        return UnifiedRecognitionPlan(rankedBySample, acceptedBySample)
    }

    private fun rankedScores(query: FloatArray): List<RecognitionIdentityScore> {
        val normalizedQuery = normalized(query)
        return referencesByStudent.map { (studentId, references) ->
            val similarities = references
                .map { cosine(normalizedQuery, it.embedding) }
                .sortedDescending()
            val bestSimilarity = similarities.first()
            val secondSimilarity = similarities.getOrNull(1)
            val combinedSimilarity = if (secondSimilarity == null) {
                bestSimilarity
            } else {
                bestSimilarity * (1f - RecognitionPolicy.SECOND_REFERENCE_WEIGHT) +
                    secondSimilarity * RecognitionPolicy.SECOND_REFERENCE_WEIGHT
            }
            RecognitionIdentityScore(
                studentId = studentId,
                studentName = references.first().studentName,
                similarity = combinedSimilarity,
                referenceCount = similarities.size,
                hasReferenceSupport = secondSimilarity == null ||
                    secondSimilarity >= RecognitionPolicy.MINIMUM_REFERENCE_SUPPORT
            )
        }.sortedWith(
            compareByDescending<RecognitionIdentityScore> { it.similarity }
                .thenBy { it.studentId }
        )
    }

    private fun acceptedScore(
        scores: List<RecognitionIdentityScore>
    ): RecognitionIdentityScore? {
        val best = scores.firstOrNull() ?: return null
        val second = scores.getOrNull(1)
        val hasMargin = second == null ||
            best.similarity - second.similarity >= RecognitionPolicy.MINIMUM_SCORE_MARGIN
        val threshold = if (best.referenceCount == 1) {
            RecognitionPolicy.SINGLE_REFERENCE_THRESHOLD
        } else {
            RecognitionPolicy.SIMILARITY_THRESHOLD
        }
        return best.takeIf {
            it.similarity >= threshold && hasMargin && it.hasReferenceSupport
        }
    }

    private fun normalized(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        require(norm > 0f) { "Un embedding no puede ser un vector cero." }
        return FloatArray(values.size) { index -> values[index] / norm }
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size) {
            "Los embeddings deben tener la misma dimension."
        }
        var similarity = 0f
        for (index in left.indices) similarity += left[index] * right[index]
        return similarity
    }
}
