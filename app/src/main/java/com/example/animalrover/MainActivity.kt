/*
 * Animal-Avoidance Rover Controller — Android App
 * =================================================
 *
 * Architecture:
 *   CameraX (live feed) -> ML Kit Object Detection (on-device, NPU-accelerated)
 *   -> filter to animal-ish detections -> decision logic (stop/turn/slow/forward)
 *   -> send short command string over Bluetooth LE to an ESP32 driving the motors
 *
 * Gradle dependencies needed (add to app/build.gradle):
 *
 *   implementation "androidx.camera:camera-core:1.3.4"
 *   implementation "androidx.camera:camera-camera2:1.3.4"
 *   implementation "androidx.camera:camera-lifecycle:1.3.4"
 *   implementation "androidx.camera:camera-view:1.3.4"
 *   implementation "com.google.mlkit:object-detection:17.0.1"
 *   implementation "com.google.mlkit:object-detection-custom:17.0.1" // if using a custom TFLite model
 *
 * Manifest permissions needed:
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *
 * NOTE: ML Kit's stock Object Detection API returns generic categories
 * (e.g. "Animal", "Food", "Plant" — not specific species). This is often
 * good enough for "is there an animal in my path" logic. If you need
 * specific species (dog vs cat vs deer), swap in a custom COCO-trained
 * TFLite model via object-detection-custom instead — the pipeline below
 * works the same either way, just change how `label` is read.
 */

package com.example.animalrover

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // --- Config ---------------------------------------------------------
    private val CONFIDENCE_THRESHOLD = 0.5f
    private val SMOOTHING_WINDOW = 3

    // Frame is normalized 0.0-1.0 in ML Kit's coordinate space per detection box
    private val CENTER_ZONE = 0.35f to 0.65f     // x-range considered "in path"
    private val CLOSE_HEIGHT_RATIO = 0.5f        // bbox height fraction => very close
    private val MED_HEIGHT_RATIO = 0.25f         // bbox height fraction => moderate distance

    // Labels ML Kit's stock detector can return that we treat as "animal".
    // With the stock (non-custom) API, categories are broad, so "Animal" is
    // usually the one you'll see. Swap in real species names if using a
    // custom COCO model instead.
    private val ANIMAL_LABELS = setOf("Animal")

    private val actionHistory = ArrayDeque<String>()
    private var bleGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    private lateinit var cameraExecutor: java.util.concurrent.ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // your layout with a PreviewView

        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
        // connectToEsp32() — call once you've discovered the ESP32's BLE address,
        // e.g. via a scan screen or a hardcoded MAC for a single dedicated rover.
    }

    // --- Camera setup -----------------------------------------------------
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification()
                .enableMultipleObjects()
                .build()
            val detector = ObjectDetection.getClient(options)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy, detector)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    // --- Per-frame processing ---------------------------------------------
    @androidx.camera.core.ExperimentalGetImage
    private fun processFrame(
        imageProxy: ImageProxy,
        detector: com.google.mlkit.vision.objects.ObjectDetector
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(image)
            .addOnSuccessListener { detectedObjects ->
                val imgWidth = image.width.toFloat()
                val imgHeight = image.height.toFloat()

                val animalDetections = detectedObjects.filter { obj ->
                    obj.labels.any { label ->
                        ANIMAL_LABELS.contains(label.text) &&
                            label.confidence >= CONFIDENCE_THRESHOLD
                    }
                }

                val rawAction = decideAction(animalDetections, imgWidth, imgHeight)
                val smoothedAction = smoothAction(rawAction)

                sendCommand(smoothedAction)
            }
            .addOnFailureListener {
                // log/ignore — don't crash the loop on a bad frame
            }
            .addOnCompleteListener {
                imageProxy.close() // always close, or CameraX stalls
            }
    }

    // --- Decision logic (same shape as the Pi version) ---------------------
    private fun decideAction(
        detections: List<com.google.mlkit.vision.objects.DetectedObject>,
        imgWidth: Float,
        imgHeight: Float
    ): String {
        if (detections.isEmpty()) return "forward"

        // Pick the detection with the largest bounding box (closest)
        val closest = detections.maxByOrNull { it.boundingBox.height() } ?: return "forward"

        val box = closest.boundingBox
        val heightRatio = box.height().toFloat() / imgHeight
        val centerX = (box.left + box.right) / 2f / imgWidth

        val inPath = centerX in CENTER_ZONE.first..CENTER_ZONE.second

        return when {
            heightRatio >= CLOSE_HEIGHT_RATIO && inPath -> "stop"
            heightRatio >= MED_HEIGHT_RATIO && inPath ->
                if (centerX >= 0.5f) "turn_left" else "turn_right"
            inPath -> "forward_slow"
            else -> "forward"
        }
    }

    // --- Smoothing: require an action to persist across recent frames -----
    private fun smoothAction(action: String): String {
        actionHistory.addLast(action)
        if (actionHistory.size > SMOOTHING_WINDOW) actionHistory.removeFirst()

        val stopCount = actionHistory.count { it == "stop" }
        if (stopCount >= (SMOOTHING_WINDOW - 1).coerceAtLeast(1)) return "stop"

        val matchCount = actionHistory.count { it == action }
        if (matchCount >= (SMOOTHING_WINDOW / 2 + 1)) return action

        return "forward"
    }

    // --- BLE command sending -------------------------------------------
    // Command protocol: single-byte codes the ESP32 will parse.
    // 'S' = stop, 'F' = forward, 'f' = forward_slow, 'L' = turn_left, 'R' = turn_right
    private fun sendCommand(action: String) {
        val code = when (action) {
            "stop" -> "S"
            "forward" -> "F"
            "forward_slow" -> "f"
            "turn_left" -> "L"
            "turn_right" -> "R"
            else -> "F"
        }

        commandCharacteristic?.let { characteristic ->
            characteristic.value = code.toByteArray(Charsets.UTF_8)
            bleGatt?.writeCharacteristic(characteristic)
        }
        // If BLE isn't connected yet, this is a no-op — add connection-state
        // handling / a UI indicator so you know the rover isn't receiving commands.
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        bleGatt?.close()
    }
}
