package com.example.animalrover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** One detection, in the pixel coordinates of the frame that was analysed. */
data class Hit(val label: String, val score: Float, val box: RectF)

/**
 * Runs the YOLO model exported by train_deer_detector.ipynb.
 *
 * Needs two files in app/src/main/assets/:
 *   deer_detector.tflite
 *   labels.txt            (one class name per line; optional, defaults to "deer")
 *
 * The exported model's output layout differs between Ultralytics versions, so
 * [decode] works out the layout from the tensor shape at runtime:
 *   [1, 4+classes, anchors]  raw, channels first   (most common)
 *   [1, anchors, 4+classes]  raw, channels last
 *   [1, N, 6]                already post-processed (x1,y1,x2,y2,score,class)
 * The shapes are written to Logcat under the tag "DeerDetector".
 */
class DeerDetector(
    context: Context,
    initialScoreThreshold: Float = 0.4f,
    private val iouThreshold: Float = 0.45f,
    private val farPass: Boolean = true,
    private val onResult: (hits: List<Hit>, frameWidth: Int, frameHeight: Int) -> Unit
) {
    /** Can be changed at runtime (e.g. lowered briefly when motion is seen). */
    @Volatile var scoreThreshold: Float = initialScoreThreshold

    private val interpreter: Interpreter
    private val labels: List<String>
    private val inW: Int
    private val inH: Int
    private val nchw: Boolean
    private val inputBuffer: ByteBuffer
    private val pixels: IntArray
    private val lbBitmap: Bitmap
    private val lbCanvas: Canvas
    private val lbPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outShape: IntArray
    private val output: Array<Array<FloatArray>>

    init {
        val modelBytes = context.assets.open("deer_detector.tflite").use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
        modelBuffer.put(modelBytes)
        modelBuffer.rewind()

        interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(4))

        val inTensor = interpreter.getInputTensor(0)
        val inShape = inTensor.shape()
        require(inTensor.dataType() == DataType.FLOAT32) {
            "Model input must be float32 but is ${inTensor.dataType()}. Re-export without quantization."
        }
        require(inShape.size == 4 && (inShape[3] == 3 || inShape[1] == 3)) {
            "Unexpected input shape ${inShape.toList()}; expected [1,h,w,3] or [1,3,h,w]."
        }
        nchw = inShape[1] == 3 && inShape[3] != 3
        inH = if (nchw) inShape[2] else inShape[1]
        inW = if (nchw) inShape[3] else inShape[2]
        inputBuffer = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(inW * inH)
        lbBitmap = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
        lbCanvas = Canvas(lbBitmap)

        outShape = interpreter.getOutputTensor(0).shape()
        require(outShape.size == 3) { "Unexpected output shape ${outShape.toList()}" }
        output = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }

        labels = try {
            context.assets.open("labels.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            listOf("deer")
        }

        Log.i(TAG, "input=${inShape.toList()} output=${outShape.toList()} labels=$labels")
    }

    /**
     * Runs one frame through the model and reports hits via [onResult] on the calling thread.
     *
     * Two passes, so distant animals show up:
     *  - near pass: the whole frame, shrunk to the model size (keeps its shape, no stretching)
     *  - far pass: a square cut from the middle of the frame (around the road ahead) at full
     *    camera resolution, so a small far-away deer keeps many more pixels
     */
    @Synchronized
    fun detect(frame: Bitmap) {
        val all = ArrayList<Hit>(runOn(frame))

        if (farPass && min(frame.width, frame.height) >= 700) {
            val side = (min(frame.width, frame.height) * 0.6f).toInt()
            val left = ((frame.width - side) / 2).coerceAtLeast(0)
            val top = (frame.height * 0.45f - side / 2f).toInt().coerceIn(0, frame.height - side)
            val crop = Bitmap.createBitmap(frame, left, top, side, side)
            for (h in runOn(crop)) {
                all.add(
                    Hit(
                        h.label, h.score,
                        RectF(h.box.left + left, h.box.top + top, h.box.right + left, h.box.bottom + top)
                    )
                )
            }
            crop.recycle()
        }

        onResult(nms(all), frame.width, frame.height)
    }

    /** One model run on [src]; returned boxes are in [src] pixel coordinates. */
    private fun runOn(src: Bitmap): List<Hit> {
        // Letterbox: keep the aspect ratio, pad the rest with grey (what the model was trained on)
        val scale = min(inW.toFloat() / src.width, inH.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val padX = (inW - dw) / 2f
        val padY = (inH - dh) / 2f
        lbCanvas.drawColor(Color.rgb(114, 114, 114))
        lbCanvas.drawBitmap(src, null, RectF(padX, padY, padX + dw, padY + dh), lbPaint)
        lbBitmap.getPixels(pixels, 0, inW, 0, 0, inW, inH)

        inputBuffer.rewind()
        if (nchw) {
            // planar: all R, then all G, then all B
            for (shift in intArrayOf(16, 8, 0)) {
                for (p in pixels) inputBuffer.putFloat(((p shr shift) and 0xFF) / 255f)
            }
        } else {
            for (p in pixels) {
                inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
                inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
                inputBuffer.putFloat((p and 0xFF) / 255f)
            }
        }
        inputBuffer.rewind()

        interpreter.run(inputBuffer, output)

        return decode().map { h ->
            val box = RectF(
                (h.box.left - padX) / scale, (h.box.top - padY) / scale,
                (h.box.right - padX) / scale, (h.box.bottom - padY) / scale
            )
            Hit(h.label, h.score, clamp(box, src.width, src.height))
        }
    }

    // --- Output decoding (boxes come back in model-input pixels) -----------
    private fun decode(): List<Hit> {
        val a = outShape[1]
        val b = outShape[2]
        val candidates = ArrayList<Hit>()

        if (b == 6 && a <= 1000) {
            // Post-processed rows: x1, y1, x2, y2, score, class
            for (i in 0 until a) {
                val r = output[0][i]
                if (r[4] < scoreThreshold) continue
                val normalized = max(max(r[0], r[1]), max(r[2], r[3])) <= 1.5f
                val sx = if (normalized) inW.toFloat() else 1f
                val sy = if (normalized) inH.toFloat() else 1f
                candidates.add(Hit(labelFor(r[5].toInt()), r[4], RectF(r[0] * sx, r[1] * sy, r[2] * sx, r[3] * sy)))
            }
            return candidates
        }

        // Raw predictions: cx, cy, w, h, then one score per class
        val channelsFirst = a < b
        val anchors = if (channelsFirst) b else a
        val channels = if (channelsFirst) a else b
        val classCount = channels - 4
        if (classCount < 1) return emptyList()

        for (i in 0 until anchors) {
            var bestClass = 0
            var bestScore = -1f
            for (c in 0 until classCount) {
                val s = if (channelsFirst) output[0][4 + c][i] else output[0][i][4 + c]
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < scoreThreshold) continue

            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            if (channelsFirst) {
                cx = output[0][0][i]; cy = output[0][1][i]; w = output[0][2][i]; h = output[0][3][i]
            } else {
                cx = output[0][i][0]; cy = output[0][i][1]; w = output[0][i][2]; h = output[0][i][3]
            }

            val normalized = max(max(cx, cy), max(w, h)) <= 1.5f
            val sx = if (normalized) inW.toFloat() else 1f
            val sy = if (normalized) inH.toFloat() else 1f
            val box = RectF(
                (cx - w / 2f) * sx, (cy - h / 2f) * sy,
                (cx + w / 2f) * sx, (cy + h / 2f) * sy
            )
            candidates.add(Hit(labelFor(bestClass), bestScore, box))
        }
        return nms(candidates)
    }

    private fun labelFor(index: Int): String = labels.getOrElse(index) { "object" }

    private fun clamp(r: RectF, w: Int, h: Int) = RectF(
        r.left.coerceIn(0f, w.toFloat()), r.top.coerceIn(0f, h.toFloat()),
        r.right.coerceIn(0f, w.toFloat()), r.bottom.coerceIn(0f, h.toFloat())
    )

    /** Greedy non-maximum suppression, per class. */
    private fun nms(hits: List<Hit>): List<Hit> {
        val sorted = hits.sortedByDescending { it.score }
        val kept = ArrayList<Hit>()
        for (h in sorted) {
            if (kept.none { it.label == h.label && iou(it.box, h.box) > iouThreshold }) kept.add(h)
            if (kept.size >= 10) break
        }
        return kept
    }

    private fun iou(p: RectF, q: RectF): Float {
        val ix = max(0f, min(p.right, q.right) - max(p.left, q.left))
        val iy = max(0f, min(p.bottom, q.bottom) - max(p.top, q.top))
        val inter = ix * iy
        val union = p.width() * p.height() + q.width() * q.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() {
        interpreter.close()
    }

    companion object {
        private const val TAG = "DeerDetector"
    }
}
