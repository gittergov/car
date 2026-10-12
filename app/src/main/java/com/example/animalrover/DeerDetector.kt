package com.example.animalrover

import android.content.Context
import android.graphics.Bitmap
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
    private val scoreThreshold: Float = 0.4f,
    private val iouThreshold: Float = 0.45f,
    private val onResult: (hits: List<Hit>, frameWidth: Int, frameHeight: Int) -> Unit
) {
    private val interpreter: Interpreter
    private val labels: List<String>
    private val inW: Int
    private val inH: Int
    private val inputBuffer: ByteBuffer
    private val pixels: IntArray
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
        require(inShape.size == 4 && inShape[3] == 3) {
            "Unexpected input shape ${inShape.toList()}; expected [1, height, width, 3]."
        }
        inH = inShape[1]
        inW = inShape[2]
        inputBuffer = ByteBuffer.allocateDirect(4 * inW * inH * 3).order(ByteOrder.nativeOrder())
        pixels = IntArray(inW * inH)

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

    /** Runs one frame through the model and reports hits via [onResult] on the calling thread. */
    @Synchronized
    fun detect(frame: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(frame, inW, inH, true)
        scaled.getPixels(pixels, 0, inW, 0, 0, inW, inH)
        if (scaled !== frame) scaled.recycle()

        inputBuffer.rewind()
        for (p in pixels) {
            inputBuffer.putFloat(((p shr 16) and 0xFF) / 255f)
            inputBuffer.putFloat(((p shr 8) and 0xFF) / 255f)
            inputBuffer.putFloat((p and 0xFF) / 255f)
        }
        inputBuffer.rewind()

        interpreter.run(inputBuffer, output)

        onResult(decode(frame.width, frame.height), frame.width, frame.height)
    }

    // --- Output decoding ----------------------------------------------------
    private fun decode(frameW: Int, frameH: Int): List<Hit> {
        val a = outShape[1]
        val b = outShape[2]
        val candidates = ArrayList<Hit>()

        if (b == 6 && a <= 1000) {
            // Post-processed rows: x1, y1, x2, y2, score, class
            for (i in 0 until a) {
                val r = output[0][i]
                if (r[4] < scoreThreshold) continue
                val normalized = max(max(r[0], r[1]), max(r[2], r[3])) <= 1.5f
                val sx = if (normalized) frameW.toFloat() else frameW.toFloat() / inW
                val sy = if (normalized) frameH.toFloat() else frameH.toFloat() / inH
                candidates.add(
                    Hit(labelFor(r[5].toInt()), r[4], clamp(RectF(r[0] * sx, r[1] * sy, r[2] * sx, r[3] * sy), frameW, frameH))
                )
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
            val sx = if (normalized) frameW.toFloat() else frameW.toFloat() / inW
            val sy = if (normalized) frameH.toFloat() else frameH.toFloat() / inH
            val box = RectF(
                (cx - w / 2f) * sx, (cy - h / 2f) * sy,
                (cx + w / 2f) * sx, (cy + h / 2f) * sy
            )
            candidates.add(Hit(labelFor(bestClass), bestScore, clamp(box, frameW, frameH)))
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
