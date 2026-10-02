package com.zen.facetrial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Draws face boxes (and a name label on one of them) on top of the camera preview. */
class FaceOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var faces: List<Rect> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private var mirror = false
    private var labelIndex = -1
    private var label: String? = null

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 46f
        isAntiAlias = true
    }
    private val textBg = Paint().apply {
        color = Color.parseColor("#AA000000")
        style = Paint.Style.FILL
    }

    fun update(
        faces: List<Rect>,
        imageWidth: Int,
        imageHeight: Int,
        mirror: Boolean,
        labelIndex: Int = -1,
        label: String? = null
    ) {
        this.faces = faces
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.mirror = mirror
        this.labelIndex = labelIndex
        this.label = label
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (faces.isEmpty()) return

        // PreviewView default scale type is FILL_CENTER
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        faces.forEachIndexed { index, r ->
            var left = r.left.toFloat()
            var right = r.right.toFloat()
            if (mirror) {
                val l = imageWidth - right
                val rr = imageWidth - left
                left = l
                right = rr
            }
            val l = left * scale + offsetX
            val t = r.top * scale + offsetY
            val rt = right * scale + offsetX
            val b = r.bottom * scale + offsetY
            canvas.drawRect(l, t, rt, b, boxPaint)

            val text = label
            if (index == labelIndex && !text.isNullOrBlank()) {
                val w = textPaint.measureText(text)
                val top = (t - 62f).coerceAtLeast(0f)
                canvas.drawRect(l, top, l + w + 24f, top + 58f, textBg)
                canvas.drawText(text, l + 12f, top + 44f, textPaint)
            }
        }
    }
}
