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
import kotlin.math.sqrt

data class LocalFaceSample(
    val bounds: Rect,
    val embedding: FloatArray
)

class LocalFaceRecognizer(context: Context) : AutoCloseable {
    companion object {
        private const val MODEL_FILE = "w600k_mbf.onnx"
        const val MODEL_ID = "w600k_mbf_v2_corrected_alignment"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_SIZE = 512
        private val INPUT_SHAPE = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        private val TARGET_EYES = floatArrayOf(
            38.2946f,
            51.6963f,
            73.5318f,
            51.5014f
        )
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val model = context.assets.open(MODEL_FILE).use { it.readBytes() }
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
        // ML Kit names eyes from the subject's perspective. ArcFace's first
        // target point is the eye at the left side of the displayed image.
        val imageLeftEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val imageRightEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position

        if (imageLeftEye != null && imageRightEye != null) {
            val sourceEyes = floatArrayOf(
                imageLeftEye.x,
                imageLeftEye.y,
                imageRightEye.x,
                imageRightEye.y
            )
            val transform = Matrix()
            if (transform.setPolyToPoly(sourceEyes, 0, TARGET_EYES, 0, 2)) {
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
