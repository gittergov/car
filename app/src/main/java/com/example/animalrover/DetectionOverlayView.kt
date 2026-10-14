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
    private var motionBox: RectF? = null
    private var alert = false
    private var frameW = 1
    private var frameH = 1

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val motionPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val alertFill = Paint().apply {
        color = Color.argb(70, 255, 0, 0)
        style = Paint.Style.FILL
    }
    private val alertBorder = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 36f
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

    /** Red tint and border over the whole preview while a deer is ahead. */
    fun setAlert(on: Boolean) {
        if (alert != on) {
            alert = on
            postInvalidate()
        }
    }

    fun setMotion(box: RectF?) {
        motionBox = box
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = max(width / frameW.toFloat(), height / frameH.toFloat())
        val offsetX = (width - frameW * scale) / 2f
        val offsetY = (height - frameH * scale) / 2f

        if (alert) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alertFill)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), alertBorder)
        }

        motionBox?.let { m ->
            canvas.drawRect(
                RectF(
                    m.left * scale + offsetX, m.top * scale + offsetY,
                    m.right * scale + offsetX, m.bottom * scale + offsetY
                ), motionPaint
            )
        }

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
