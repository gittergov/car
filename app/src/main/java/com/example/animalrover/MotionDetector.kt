package com.example.animalrover

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs

/**
 * Cheap frame-differencing motion finder. Call from ONE thread only.
 *
 * Each frame is shrunk to a small grayscale grid and compared with the previous
 * one. Cells that changed a lot are grouped into a box. If most of the picture
 * changed at once, the camera itself is moving (or the light changed), so the
 * result is ignored. Scattered noise is ignored too: changed cells must be
 * bunched together.
 */
class MotionDetector(
    private val gridW: Int = 64,
    private val gridH: Int = 48,
    private val pixelDelta: Int = 28,          // brightness change (0-255) that counts as "changed"
    private val egoMotionFraction: Float = 0.30f, // more than this share changed = camera moving
    private val minChangedCells: Int = 8,
    private val minDensity: Float = 0.12f      // changed cells / box area; rejects scattered noise
) {
    private var prev: IntArray? = null
    private var cur = IntArray(gridW * gridH)
    private val px = IntArray(gridW * gridH)

    /** Returns a box in the frame's pixel coordinates around the moving area, or null. */
    fun detect(frame: Bitmap): RectF? {
        val small = Bitmap.createScaledBitmap(frame, gridW, gridH, true)
        small.getPixels(px, 0, gridW, 0, 0, gridW, gridH)
        if (small !== frame) small.recycle()

        for (i in px.indices) {
            val p = px[i]
            cur[i] = (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        }

        val before = prev
        prev = cur
        cur = before ?: IntArray(gridW * gridH)
        if (before == null) return null

        val now = prev!!
        var changed = 0
        var minX = gridW
        var minY = gridH
        var maxX = -1
        var maxY = -1
        for (y in 0 until gridH) {
            for (x in 0 until gridW) {
                val i = y * gridW + x
                if (abs(now[i] - before[i]) > pixelDelta) {
                    changed++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (changed < minChangedCells) return null
        if (changed.toFloat() / (gridW * gridH) > egoMotionFraction) return null
        val boxCells = (maxX - minX + 1) * (maxY - minY + 1)
        if (changed.toFloat() / boxCells < minDensity) return null

        val sx = frame.width.toFloat() / gridW
        val sy = frame.height.toFloat() / gridH
        return RectF(minX * sx, minY * sy, (maxX + 1) * sx, (maxY + 1) * sy)
    }

    fun reset() {
        prev = null
    }
}
