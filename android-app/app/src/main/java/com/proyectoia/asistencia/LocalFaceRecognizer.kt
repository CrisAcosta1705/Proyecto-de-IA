package com.proyectoia.asistencia

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.sqrt

data class LocalFaceSample(
    val bounds: Rect,
    val embedding: FloatArray
)

class LocalFaceRecognizer(context: Context) : AutoCloseable {
    companion object {
        const val MODEL_ID = RecognitionPolicy.PROFILE_ID
        private const val INPUT_SIZE = RecognitionPolicy.INPUT_SIZE
        private const val EMBEDDING_SIZE = RecognitionPolicy.EMBEDDING_SIZE
        private val INPUT_SHAPE = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        private val TARGET_LANDMARKS = arrayOf(
            floatArrayOf(38.2946f, 51.6963f),
            floatArrayOf(73.5318f, 51.5014f),
            floatArrayOf(56.0252f, 71.7366f),
            floatArrayOf(41.5493f, 92.3655f),
            floatArrayOf(70.7299f, 92.2041f)
        )
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val model = context.assets.open(RecognitionPolicy.MODEL_FILE).use { it.readBytes() }
        val modelSha256 = MessageDigest.getInstance("SHA-256")
            .digest(model)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        require(modelSha256 == RecognitionPolicy.MODEL_SHA256) {
            "El modelo facial local no coincide con el perfil de reconocimiento."
        }
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(model, options)
        }
        inputName = session.inputNames.first()
    }

    fun extract(bitmap: Bitmap, face: Face): LocalFaceSample {
        val alignedFace = alignFace(bitmap, face)
        val input = bitmapToTensor(alignedFace)
        val embedding = runModel(input)
        require(embedding.size == EMBEDDING_SIZE) {
            "El modelo facial devolvio ${embedding.size} valores; se esperaban $EMBEDDING_SIZE."
        }
        normalize(embedding)
        return LocalFaceSample(Rect(face.boundingBox), embedding)
    }

    fun warmUp() {
        val output = runModel(FloatArray(INPUT_SIZE * INPUT_SIZE * 3))
        require(output.size == EMBEDDING_SIZE) {
            "No se pudo validar el modelo facial local."
        }
    }

    override fun close() {
        session.close()
    }

    private fun runModel(input: FloatArray): FloatArray {
        return OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            INPUT_SHAPE
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result[0].value as Array<FloatArray>
                output[0].copyOf()
            }
        }
    }

    private fun alignFace(bitmap: Bitmap, face: Face): Bitmap {
        val output = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val landmarks = arcFaceLandmarks(face)
        if (landmarks != null) {
            canvas.drawBitmap(bitmap, similarityTransform(landmarks), paint)
            return output
        }
        val imageLeftEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val imageRightEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        if (imageLeftEye != null && imageRightEye != null) {
            val sourceEyes = floatArrayOf(
                imageLeftEye.x,
                imageLeftEye.y,
                imageRightEye.x,
                imageRightEye.y
            )
            val targetEyes = floatArrayOf(
                TARGET_LANDMARKS[0][0],
                TARGET_LANDMARKS[0][1],
                TARGET_LANDMARKS[1][0],
                TARGET_LANDMARKS[1][1]
            )
            val transform = Matrix()
            if (transform.setPolyToPoly(sourceEyes, 0, targetEyes, 0, 2)) {
                canvas.drawBitmap(bitmap, transform, paint)
                return output
            }
        }

        val source = expandedSquare(face.boundingBox, bitmap.width, bitmap.height)
        canvas.drawBitmap(
            bitmap,
            source,
            RectF(0f, 0f, INPUT_SIZE.toFloat(), INPUT_SIZE.toFloat()),
            paint
        )
        return output
    }

    private fun arcFaceLandmarks(face: Face): Array<FloatArray>? {
        // ML Kit names left/right from the subject's perspective. ArcFace uses
        // the order seen in the image: left eye, right eye, nose, mouth corners.
        val types = intArrayOf(
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.LEFT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_LEFT
        )
        return Array(types.size) { index ->
            val point = face.getLandmark(types[index])?.position ?: return null
            floatArrayOf(point.x, point.y)
        }
    }

    private fun similarityTransform(source: Array<FloatArray>): Matrix {
        val sourceCenterX = source.map { it[0] }.average().toFloat()
        val sourceCenterY = source.map { it[1] }.average().toFloat()
        val targetCenterX = TARGET_LANDMARKS.map { it[0] }.average().toFloat()
        val targetCenterY = TARGET_LANDMARKS.map { it[1] }.average().toFloat()
        var denominator = 0f
        var aNumerator = 0f
        var bNumerator = 0f
        source.indices.forEach { index ->
            val sourceX = source[index][0] - sourceCenterX
            val sourceY = source[index][1] - sourceCenterY
            val targetX = TARGET_LANDMARKS[index][0] - targetCenterX
            val targetY = TARGET_LANDMARKS[index][1] - targetCenterY
            denominator += sourceX * sourceX + sourceY * sourceY
            aNumerator += sourceX * targetX + sourceY * targetY
            bNumerator += sourceX * targetY - sourceY * targetX
        }
        require(denominator > 0f) { "No se pudieron alinear los puntos faciales." }
        val a = aNumerator / denominator
        val b = bNumerator / denominator
        val translateX = targetCenterX - a * sourceCenterX + b * sourceCenterY
        val translateY = targetCenterY - b * sourceCenterX - a * sourceCenterY
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    a, -b, translateX,
                    b, a, translateY,
                    0f, 0f, 1f
                )
            )
        }
    }

    private fun expandedSquare(face: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val size = (maxOf(face.width(), face.height()) * 1.35f).toInt().coerceAtLeast(1)
        val centerX = face.centerX()
        val centerY = face.centerY() - (face.height() * 0.05f).toInt()
        var left = centerX - size / 2
        var top = centerY - size / 2
        var right = left + size
        var bottom = top + size

        if (left < 0) {
            right -= left
            left = 0
        }
        if (top < 0) {
            bottom -= top
            top = 0
        }
        if (right > imageWidth) {
            left -= right - imageWidth
            right = imageWidth
        }
        if (bottom > imageHeight) {
            top -= bottom - imageHeight
            bottom = imageHeight
        }
        return Rect(left.coerceAtLeast(0), top.coerceAtLeast(0), right, bottom)
    }

    private fun bitmapToTensor(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val channelSize = INPUT_SIZE * INPUT_SIZE
        val input = FloatArray(channelSize * 3)
        pixels.forEachIndexed { index, pixel ->
            input[index] = (Color.red(pixel) - 127.5f) / 127.5f
            input[channelSize + index] = (Color.green(pixel) - 127.5f) / 127.5f
            input[channelSize * 2 + index] = (Color.blue(pixel) - 127.5f) / 127.5f
        }
        return input
    }

    private fun normalize(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        require(norm > 0f) { "El modelo genero un descriptor facial vacio." }
        for (index in values.indices) values[index] /= norm
        return values
    }
}
