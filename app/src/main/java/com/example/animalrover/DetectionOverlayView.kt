package com.example.animalrover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws bounding boxes + labels over the camera preview for whatever
 * animals were detected in the most recent frame.
 *
 * Call updateDetections(...) from MainActivity's processFrame() each time
 * a new detection pass completes, passing boxes already converted into
 * this view's coordinate space (accounting for preview scale/rotation).
 */
class DetectionOverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private var boxes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 40f
    }

    fun updateDetections(newBoxes: List<RectF>, newLabels: List<String>) {
        boxes = newBoxes
        labels = newLabels
        postInvalidate() // trigger a redraw on the UI thread
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        boxes.forEachIndexed { i, box ->
            canvas.drawRect(box, boxPaint)
            val label = labels.getOrElse(i) { "" }
            canvas.drawText(label, box.left, (box.top - 10f).coerceAtLeast(20f), textPaint)
        }
    }
}
