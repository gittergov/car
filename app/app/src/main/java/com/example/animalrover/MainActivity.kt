package com.example.animalrover

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
    private val CLOSE_HEIGHT_RATIO = 0.5f   // box taller than this share of frame = very close
    private val MED_HEIGHT_RATIO = 0.25f
    private val SMOOTHING_WINDOW = 3
    private val RESEND_MS = 400L            // re-send the same command at most this often

    // --- State --------------------------------------------------------------
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var detector: DeerDetector
    private lateinit var ble: BleController

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var statusText: TextView
    private lateinit var bleStatusText: TextView

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null

    @Volatile private var manualStop = false
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

        detector = DeerDetector(this) { hits, w, h -> onDetections(hits, w, h) }

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

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
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
            detector.detect(upright)
        } finally {
            proxy.close()
        }
    }

    // --- Detections -> action ----------------------------------------------
    private fun onDetections(hits: List<Hit>, frameW: Int, frameH: Int) {
        overlayView.update(hits, frameW, frameH)
        if (manualStop) return

        val raw = decide(hits, frameW, frameH)
        val action = smooth(raw)

        runOnUiThread {
            statusText.text = if (hits.isEmpty()) "No deer detected -> $action"
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
        detector.close()
        ble.disconnect()
    }
}
