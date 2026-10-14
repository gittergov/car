package com.example.animalrover

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera -> custom deer model (MediaPipe) -> decision logic -> BLE command to ESP32.
 *
 * Command codes (must match esp32_motor_controller.ino):
 *   S stop, F forward, f forward slow, L turn left, R turn right
 */
class MainActivity : AppCompatActivity() {

    // --- Tuning -------------------------------------------------------------
    private val CENTER_MIN = 0.35f          // horizontal band counted as "in my path"
    private val CENTER_MAX = 0.65f
    private val CLOSE_HEIGHT_RATIO = 0.30f  // roughly 9 yards at 2x zoom, phone held landscape
    private val MED_HEIGHT_RATIO = 0.18f    // roughly 12-15 yards
    private val SMOOTHING_WINDOW = 3
    private val MOTION_HOLD_MS = 1500L     // pause this long when something moves ahead
    private val MOTION_COOLDOWN_MS = 3000L  // then ignore motion this long (wind, branches)
    private val ZOOM = 1.5f                  // zoom in a little (smaller = wider view, bigger = reaches farther)
    private val RESEND_MS = 400L            // re-send the same command at most this often

    // --- State --------------------------------------------------------------
    private lateinit var cameraExecutor: ExecutorService
    private var detector: DeerDetector? = null
    private lateinit var ble: BleController

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var statusText: TextView
    private lateinit var bleStatusText: TextView

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null

    @Volatile private var manualStop = false
    private val motion = MotionDetector()
    private var tone: ToneGenerator? = null
    private var lastBeepAt = 0L
    private val deerHistory = ArrayDeque<Boolean>()
    private val motionHistory = ArrayDeque<Boolean>()
    @Volatile private var motionHoldUntil = 0L
    private var motionCooldownUntil = 0L
    private val history = ArrayDeque<String>()
    private var lastSent = ""
    private var lastSentAt = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) startCamera()
            else statusText.text = "Camera permission is required"
            if (ble.hasRequiredPermissions()) ble.startScan()
            else bleStatusText.text = "BLE: permission denied"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // never sleep while the app is open

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        bleStatusText = findViewById(R.id.bleStatusText)

        findViewById<Button>(R.id.emergencyStopButton).setOnClickListener {
            manualStop = true
            sendCommand("S", force = true)
            statusText.text = "MANUAL STOP"
        }
        findViewById<Button>(R.id.resumeButton).setOnClickListener {
            manualStop = false
            statusText.text = "Auto mode"
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 100) } catch (t: Throwable) { null }

        try {
            detector = DeerDetector(this, initialScoreThreshold = 0.3f) { hits, w, h -> onDetections(hits, w, h) }
        } catch (t: Throwable) {
            statusText.text = "MODEL ERROR: " + t.javaClass.simpleName + ": " + (t.message ?: "").take(300)
        }

        ble = BleController(
            context = this,
            onConnected = { runOnUiThread { bleStatusText.text = "BLE: connected, setting up..." } },
            onDisconnected = {
                commandChar = null
                runOnUiThread { bleStatusText.text = "BLE: disconnected" }
            },
            onReady = { g, c ->
                gatt = g
                commandChar = c
                runOnUiThread { bleStatusText.text = "BLE: ready" }
            }
        )

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    // --- Camera -------------------------------------------------------------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Full-HD frames so far-away animals keep enough pixels for the model
            val hdSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(hdSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }

            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
            val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
            camera.cameraControl.setZoomRatio(minOf(ZOOM, maxZoom))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val rotation = proxy.imageInfo.rotationDegrees
            val bitmap = proxy.toBitmap()
            val upright = if (rotation != 0) {
                Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(rotation.toFloat()) }, true
                )
            } else bitmap
            updateMotion(upright)
            detector?.detect(upright)
        } catch (t: Throwable) {
            runOnUiThread { statusText.text = "DETECT ERROR: " + t.javaClass.simpleName + ": " + (t.message ?: "").take(300) }
        } finally {
            proxy.close()
        }
    }

    // --- Motion -------------------------------------------------------------
    /** Runs on the camera thread, right before the deer detector sees the same frame. */
    private fun updateMotion(frame: Bitmap) {
        val box: RectF? = motion.detect(frame)
        overlayView.setMotion(box)

        val inPath = box != null && (box.centerX() / frame.width) in 0.2f..0.8f
        motionHistory.addLast(inPath)
        if (motionHistory.size > 3) motionHistory.removeFirst()
        val confirmed = motionHistory.count { it } >= 2

        val now = System.currentTimeMillis()
        if (confirmed && now > motionCooldownUntil && !manualStop) {
            motionHoldUntil = now + MOTION_HOLD_MS
            motionCooldownUntil = motionHoldUntil + MOTION_COOLDOWN_MS
            motionHistory.clear()
        }
        // While pausing for motion, look harder for a deer
        detector?.scoreThreshold = if (now < motionHoldUntil) 0.18f else 0.3f
    }

    // --- Detections -> action ----------------------------------------------
    private fun onDetections(hits: List<Hit>, frameW: Int, frameH: Int) {
        overlayView.update(hits, frameW, frameH)

        // Warn the driver: red screen + beep for a deer ahead (works with or without BLE)
        // Any confident deer anywhere in view, seen in 2 of the last 3 frames (one-frame mistakes stay quiet)
        val seenNow = hits.any { it.score >= 0.5f }
        deerHistory.addLast(seenNow)
        if (deerHistory.size > 3) deerHistory.removeFirst()
        val deerAhead = deerHistory.count { it } >= 2
        overlayView.setAlert(deerAhead)
        val nowMs = System.currentTimeMillis()
        if (deerAhead && nowMs - lastBeepAt > 1500) {
            lastBeepAt = nowMs
            tone?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 600)
        }

        if (manualStop) return

        val pausingForMotion = hits.isEmpty() && System.currentTimeMillis() < motionHoldUntil
        val raw = if (pausingForMotion) "stop" else decide(hits, frameW, frameH)
        val action = smooth(raw)

        runOnUiThread {
            statusText.text = if (pausingForMotion) "Motion ahead, pausing to look -> $action"
            else if (hits.isEmpty()) "No deer detected -> $action"
            else "${hits.first().label} detected -> $action"
        }

        sendCommand(
            when (action) {
                "stop" -> "S"
                "turn_left" -> "L"
                "turn_right" -> "R"
                "forward_slow" -> "f"
                else -> "F"
            }
        )
    }

    private fun decide(hits: List<Hit>, w: Int, h: Int): String {
        if (hits.isEmpty()) return "forward"
        // The tallest box is the closest animal
        val closest = hits.maxByOrNull { it.box.height() } ?: return "forward"

        val heightRatio = closest.box.height() / h
        val centerX = closest.box.centerX() / w
        val inPath = centerX in CENTER_MIN..CENTER_MAX

        return when {
            heightRatio >= CLOSE_HEIGHT_RATIO && inPath -> "stop"
            heightRatio >= MED_HEIGHT_RATIO && inPath ->
                if (centerX >= 0.5f) "turn_left" else "turn_right"   // steer away from it
            inPath -> "forward_slow"
            else -> "forward"
        }
    }

    /** Act on an action only if it holds across several recent frames; stops win quickly. */
    private fun smooth(action: String): String {
        synchronized(history) {
            history.addLast(action)
            if (history.size > SMOOTHING_WINDOW) history.removeFirst()

            if (history.count { it == "stop" } >= (SMOOTHING_WINDOW - 1).coerceAtLeast(1)) return "stop"
            if (history.count { it == action } >= SMOOTHING_WINDOW / 2 + 1) return action
            return "forward"
        }
    }

    // --- BLE ----------------------------------------------------------------
    private fun sendCommand(code: String, force: Boolean = false) {
        val ch = commandChar ?: return
        val g = gatt ?: return
        val now = System.currentTimeMillis()
        // Avoid flooding BLE with identical writes; always send changes right away
        if (!force && code == lastSent && now - lastSentAt < RESEND_MS) return
        lastSent = code
        lastSentAt = now
        @Suppress("DEPRECATION")
        run {
            ch.value = code.toByteArray(Charsets.UTF_8)
            g.writeCharacteristic(ch)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sendCommand("S", force = true)
        cameraExecutor.shutdown()
        detector?.close()
        tone?.release()
        ble.disconnect()
    }
}
