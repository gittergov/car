package com.example.animalrover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Draws detection boxes over the camera preview.
 *
 * Boxes arrive in camera-frame pixels. The preview uses FILL_CENTER (the frame
 * is scaled up until it covers the view, extra is cropped), so the same
 * scale + offset is applied here to line the boxes up with what is on screen.
 */
class DetectionOverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var hits: List<Hit> = emptyList()
    private var frameW = 1
    private var frameH = 1

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 40f
    }

    fun update(newHits: List<Hit>, frameWidth: Int, frameHeight: Int) {
        hits = newHits
        frameW = frameWidth.coerceAtLeast(1)
        frameH = frameHeight.coerceAtLeast(1)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = max(width / frameW.toFloat(), height / frameH.toFloat())
        val offsetX = (width - frameW * scale) / 2f
        val offsetY = (height - frameH * scale) / 2f

        for (h in hits) {
            val r = RectF(
                h.box.left * scale + offsetX,
                h.box.top * scale + offsetY,
                h.box.right * scale + offsetX,
                h.box.bottom * scale + offsetY
            )
            canvas.drawRect(r, boxPaint)
            canvas.drawText(
                "${h.label} ${(h.score * 100).toInt()}%",
                r.left, (r.top - 10f).coerceAtLeast(40f), textPaint
            )
        }
    }
}
