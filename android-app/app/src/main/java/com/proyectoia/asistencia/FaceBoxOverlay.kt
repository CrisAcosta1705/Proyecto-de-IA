package com.proyectoia.asistencia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FaceBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxes = mutableListOf<Rect>()
    private var imageWidth = 1
    private var imageHeight = 1

    private val boxPaint = Paint().apply {
        color = Color.rgb(23, 107, 91)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    fun setFaces(boxes: List<Rect>, imageWidth: Int, imageHeight: Int) {
        this.boxes.clear()
        this.boxes.addAll(boxes)
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()

        boxes.forEach { box ->
            val rect = RectF(
                box.left * scaleX,
                box.top * scaleY,
                box.right * scaleX,
                box.bottom * scaleY
            )
            canvas.drawRoundRect(rect, 10f, 10f, boxPaint)
        }
    }
}
