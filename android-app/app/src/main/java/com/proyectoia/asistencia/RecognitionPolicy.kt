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
    val embedding: FloatArray,
    val captureId: String = "",
    val variant: String = "ORIGINAL"
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
    private data class StudentGallery(
        val name: String,
        val captures: Map<String, List<FloatArray>>
    )

    private val referencesByStudent: Map<String, StudentGallery> = references
        .mapIndexed { index, item ->
            item.copy(
                embedding = normalized(item.embedding),
                captureId = item.captureId.ifBlank { "legacy-$index" }
            )
        }
        .groupBy { it.studentId }
        .mapValues { (_, items) ->
            StudentGallery(
                items.first().studentName,
                items.groupBy { it.captureId }.mapValues { (_, variants) ->
                    variants.map { it.embedding }
                }
            )
        }

    fun plan(queries: List<FloatArray>): UnifiedRecognitionPlan {
        val rankedBySample = queries.map(::rankedScores)
        if (queries.isEmpty()) return UnifiedRecognitionPlan(emptyList(), emptyList())
        val studentIds = referencesByStudent.keys.sorted()
        val scoreMaps = rankedBySample.map { scores -> scores.associateBy { it.studentId } }
        val invalid = -1_000_000f
        val weights = queries.indices.map { sampleIndex ->
            MutableList(studentIds.size + queries.size) { invalid }.apply {
                studentIds.forEachIndexed { studentIndex, studentId ->
                    val score = scoreMaps[sampleIndex][studentId]
                    if (score != null && score.hasReferenceSupport) {
                        val threshold = thresholdFor(score)
                        if (score.similarity >= threshold) {
                            this[studentIndex] = score.similarity - threshold + 0.000001f
                        }
                    }
                }
                this[studentIds.size + sampleIndex] = 0f
            }
        }
        val assignments = maximumWeightAssignment(weights)
        var claimed = assignments.map { column -> studentIds.getOrNull(column) }
            .filterNotNull()
            .toSet()
        for (iteration in 0..queries.size) {
            val acceptedClaims = assignments.mapIndexed { sampleIndex, column ->
                val studentId = studentIds.getOrNull(column) ?: return@mapIndexed null
                val selected = scoreMaps[sampleIndex].getValue(studentId)
                val bestCompetitor = rankedBySample[sampleIndex]
                    .asSequence()
                    .filter { it.studentId != studentId && it.studentId !in claimed }
                    .maxOfOrNull { it.similarity }
                studentId.takeIf {
                    bestCompetitor == null ||
                        selected.similarity - bestCompetitor >=
                        RecognitionPolicy.MINIMUM_SCORE_MARGIN
                }
            }.filterNotNull().toSet()
            if (acceptedClaims == claimed) break
            claimed = acceptedClaims
        }
        val accepted = assignments.mapIndexed { sampleIndex, column ->
            val studentId = studentIds.getOrNull(column) ?: return@mapIndexed null
            val selected = scoreMaps[sampleIndex].getValue(studentId)
            val bestAvailableCompetitor = rankedBySample[sampleIndex]
                .asSequence()
                .filter { it.studentId != studentId && it.studentId !in claimed }
                .maxOfOrNull { it.similarity }
            selected.takeIf {
                studentId in claimed && (
                    bestAvailableCompetitor == null ||
                        selected.similarity - bestAvailableCompetitor >=
                        RecognitionPolicy.MINIMUM_SCORE_MARGIN
                    )
            }
        }
        return UnifiedRecognitionPlan(rankedBySample, accepted)
    }

    private fun rankedScores(query: FloatArray): List<RecognitionIdentityScore> {
        val normalizedQuery = normalized(query)
        return referencesByStudent.map { (studentId, gallery) ->
            val similarities = gallery.captures.values.map { variants ->
                variants.maxOf { cosine(normalizedQuery, it) }
            }.sortedDescending()
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
                studentName = gallery.name,
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

    private fun thresholdFor(score: RecognitionIdentityScore): Float =
        if (score.referenceCount == 1) {
            RecognitionPolicy.SINGLE_REFERENCE_THRESHOLD
        } else {
            RecognitionPolicy.SIMILARITY_THRESHOLD
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

    private fun maximumWeightAssignment(weights: List<List<Float>>): IntArray {
        val rowCount = weights.size
        if (rowCount == 0) return IntArray(0)
        val columnCount = weights.first().size
        require(columnCount >= rowCount && weights.all { it.size == columnCount })
        val maxWeight = weights.maxOf { row -> row.max() }
        val costs = weights.map { row -> row.map { maxWeight - it } }
        val u = FloatArray(rowCount + 1)
        val v = FloatArray(columnCount + 1)
        val p = IntArray(columnCount + 1)
        val way = IntArray(columnCount + 1)
        for (row in 1..rowCount) {
            p[0] = row
            var column0 = 0
            val minimum = FloatArray(columnCount + 1) { Float.POSITIVE_INFINITY }
            val used = BooleanArray(columnCount + 1)
            do {
                used[column0] = true
                val currentRow = p[column0]
                var delta = Float.POSITIVE_INFINITY
                var column1 = 0
                for (column in 1..columnCount) {
                    if (used[column]) continue
                    val current = costs[currentRow - 1][column - 1] -
                        u[currentRow] - v[column]
                    if (current < minimum[column]) {
                        minimum[column] = current
                        way[column] = column0
                    }
                    if (minimum[column] < delta) {
                        delta = minimum[column]
                        column1 = column
                    }
                }
                for (column in 0..columnCount) {
                    if (used[column]) {
                        u[p[column]] += delta
                        v[column] -= delta
                    } else {
                        minimum[column] -= delta
                    }
                }
                column0 = column1
            } while (p[column0] != 0)
            do {
                val column1 = way[column0]
                p[column0] = p[column1]
                column0 = column1
            } while (column0 != 0)
        }
        return IntArray(rowCount) { -1 }.also { assignment ->
            for (column in 1..columnCount) {
                if (p[column] != 0) assignment[p[column] - 1] = column - 1
            }
        }
    }
}
