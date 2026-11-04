package com.proyectoia.asistencia

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

enum class FaceBoxState {
    DETECTED,
    RECOGNIZED,
    UNKNOWN,
    GUIDANCE_VALID,
    GUIDANCE_INVALID
}

data class FaceOverlayBox(
    val bounds: Rect,
    val state: FaceBoxState,
    val label: String? = null
)

class FaceBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxes = mutableListOf<FaceOverlayBox>()
    private var imageWidth = 1
    private var imageHeight = 1
    private var mirrorHorizontally = false

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.scaledDensity
        isAntiAlias = true
    }
    private val labelBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setFaces(
        boxes: List<Rect>,
        imageWidth: Int,
        imageHeight: Int,
        mirrorHorizontally: Boolean = false
    ) {
        setResults(
            boxes.map { FaceOverlayBox(it, FaceBoxState.DETECTED) },
            imageWidth,
            imageHeight,
            mirrorHorizontally
        )
    }

    fun setResults(
        boxes: List<FaceOverlayBox>,
        imageWidth: Int,
        imageHeight: Int,
        mirrorHorizontally: Boolean = false
    ) {
        this.boxes.clear()
        this.boxes.addAll(boxes)
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)
        this.mirrorHorizontally = mirrorHorizontally
        invalidate()
    }

    fun clear() {
        boxes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scale = maxOf(
            width.toFloat() / imageWidth.toFloat(),
            height.toFloat() / imageHeight.toFloat()
        )
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        boxes.forEach { item ->
            val box = item.bounds
            val left = if (mirrorHorizontally) imageWidth - box.right else box.left
            val right = if (mirrorHorizontally) imageWidth - box.left else box.right
            val rect = RectF(
                left * scale + offsetX,
                box.top * scale + offsetY,
                right * scale + offsetX,
                box.bottom * scale + offsetY
            )
            boxPaint.color = colorFor(item.state)
            canvas.drawRoundRect(rect, 10f, 10f, boxPaint)
            item.label?.let { drawLabel(canvas, rect, it, boxPaint.color) }
        }
    }

    private fun colorFor(state: FaceBoxState): Int = when (state) {
        FaceBoxState.DETECTED -> Color.rgb(23, 107, 91)
        FaceBoxState.RECOGNIZED,
        FaceBoxState.GUIDANCE_VALID -> Color.rgb(22, 145, 69)
        FaceBoxState.UNKNOWN,
        FaceBoxState.GUIDANCE_INVALID -> Color.rgb(198, 40, 40)
    }

    private fun drawLabel(canvas: Canvas, box: RectF, label: String, color: Int) {
        val padding = 6f * resources.displayMetrics.density
        val availableWidth = (width - box.left - padding * 2).coerceAtLeast(1f)
        val fittedLabel = fitText(label, availableWidth)
        val textWidth = labelPaint.measureText(fittedLabel)
        val textHeight = labelPaint.fontMetrics.run { bottom - top }
        val labelBottom = box.top.coerceAtLeast(textHeight + padding * 2)
        val background = RectF(
            box.left,
            labelBottom - textHeight - padding * 2,
            box.left + textWidth + padding * 2,
            labelBottom
        )
        labelBackgroundPaint.color = color
        canvas.drawRoundRect(background, 6f, 6f, labelBackgroundPaint)
        canvas.drawText(
            fittedLabel,
            background.left + padding,
            background.bottom - padding - labelPaint.fontMetrics.bottom,
            labelPaint
        )
    }

    private fun fitText(value: String, maxWidth: Float): String {
        if (labelPaint.measureText(value) <= maxWidth) return value
        val suffix = "..."
        val suffixWidth = labelPaint.measureText(suffix)
        val count = labelPaint.breakText(
            value,
            true,
            (maxWidth - suffixWidth).coerceAtLeast(1f),
            null
        )
        return value.take(count.coerceAtLeast(1)) + suffix
    }
}
