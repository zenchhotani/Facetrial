package com.zen.facetrial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Draws face boxes on top of the camera preview. */
class FaceOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var faces: List<Rect> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private var mirror = false

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    fun update(faces: List<Rect>, imageWidth: Int, imageHeight: Int, mirror: Boolean) {
        this.faces = faces
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.mirror = mirror
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (faces.isEmpty()) return

        // PreviewView default scale type is FILL_CENTER
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val offsetX = (width - imageWidth * scale) / 2f
        val offsetY = (height - imageHeight * scale) / 2f

        for (r in faces) {
            var left = r.left.toFloat()
            var right = r.right.toFloat()
            if (mirror) {
                val l = imageWidth - right
                val rr = imageWidth - left
                left = l
                right = rr
            }
            canvas.drawRect(
                left * scale + offsetX,
                r.top * scale + offsetY,
                right * scale + offsetX,
                r.bottom * scale + offsetY,
                paint
            )
        }
    }
}
