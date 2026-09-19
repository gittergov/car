#!/usr/bin/env bash
# Sets up the Android project folder structure and drops in the rover app files.
# Run this from the ROOT of your cloned starter-template repo.
set -e

mkdir -p "app/src/main/java/com/example/animalrover"
cat > "app/src/main/java/com/example/animalrover/MainActivity.kt" << 'ROVER_EOF'
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
ROVER_EOF
echo "Created app/src/main/java/com/example/animalrover/MainActivity.kt"

mkdir -p "app/src/main/java/com/example/animalrover"
cat > "app/src/main/java/com/example/animalrover/BleController.kt" << 'ROVER_EOF'
/*
 * BLE Scan & Connect — Animal-Avoidance Rover
 * =============================================
 *
 * Add this logic into MainActivity.kt (or a separate BleController class,
 * shown here as its own class for clarity — instantiate it from MainActivity).
 *
 * Flow:
 *   1. Check/request Bluetooth + location permissions (Android needs
 *      location permission for BLE scanning on many versions)
 *   2. Scan for a device advertising our known service UUID
 *      ("AnimalAvoidanceRover" from the ESP32 sketch)
 *   3. Connect, discover services, grab the writable characteristic
 *   4. Expose a simple sendCommand(code: String) function back to MainActivity
 *
 * Manifest permissions needed (add alongside camera permission):
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *   <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
 *
 * (BLUETOOTH_SCAN / BLUETOOTH_CONNECT are Android 12+ runtime permissions;
 * ACCESS_FINE_LOCATION is required for scanning on older versions too.)
 */

package com.example.animalrover

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class BleController(
    private val context: Context,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onReady: (BluetoothGatt, BluetoothGattCharacteristic) -> Unit
) {
    companion object {
        private const val TAG = "BleController"
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private const val TARGET_DEVICE_NAME = "AnimalAvoidanceRover"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var isScanning = false

    // --- Permission check helper --------------------------------------
    fun hasRequiredPermissions(): Boolean {
        val scanPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val connectPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        val locationPerm = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return scanPerm && connectPerm && locationPerm
    }
    // Call ActivityCompat.requestPermissions(...) from MainActivity with these
    // three permission strings if hasRequiredPermissions() returns false,
    // then call startScan() again from onRequestPermissionsResult once granted.

    // --- Scanning -----------------------------------------------------
    @Suppress("MissingPermission") // guarded by hasRequiredPermissions() check at call site
    fun startScan() {
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing BLE/location permissions — request them before scanning.")
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is off or unavailable.")
            return
        }

        scanner = adapter.bluetoothLeScanner
        val filters = listOf(
            android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        scanner?.startScan(filters, settings, scanCallback)
        Log.i(TAG, "BLE scan started, looking for $TARGET_DEVICE_NAME")
    }

    @Suppress("MissingPermission")
    fun stopScan() {
        if (isScanning) {
            scanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        @Suppress("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == TARGET_DEVICE_NAME) {
                Log.i(TAG, "Found rover device, connecting...")
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            isScanning = false
        }
    }

    // --- Connecting -----------------------------------------------------
    @Suppress("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @Suppress("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to rover, discovering services...")
                    onConnected()
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Disconnected from rover")
                    onDisconnected()
                    g.close()
                    // Optional: auto-retry connecting after a short delay,
                    // since a dropped link should not leave the rover silently
                    // stuck without commands.
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = g.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic != null) {
                Log.i(TAG, "Rover characteristic ready — commands can now be sent.")
                onReady(g, characteristic)
            } else {
                Log.e(TAG, "Expected characteristic not found on rover.")
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Command write failed: $status")
            }
        }
    }

    @Suppress("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}

/*
 * --- Wiring this into MainActivity.kt --------------------------------
 *
 * In onCreate(), after checking permissions:
 *
 *   private lateinit var bleController: BleController
 *
 *   bleController = BleController(
 *       context = this,
 *       onConnected = { runOnUiThread { /* update a "connected" indicator */ } },
 *       onDisconnected = { runOnUiThread { /* update a "disconnected" indicator */ } },
 *       onReady = { gatt, characteristic ->
 *           bleGatt = gatt
 *           commandCharacteristic = characteristic
 *       }
 *   )
 *
 *   if (bleController.hasRequiredPermissions()) {
 *       bleController.startScan()
 *   } else {
 *       ActivityCompat.requestPermissions(
 *           this,
 *           arrayOf(
 *               Manifest.permission.BLUETOOTH_SCAN,
 *               Manifest.permission.BLUETOOTH_CONNECT,
 *               Manifest.permission.ACCESS_FINE_LOCATION
 *           ),
 *           REQUEST_CODE_BLE_PERMISSIONS
 *       )
 *   }
 */
ROVER_EOF
echo "Created app/src/main/java/com/example/animalrover/BleController.kt"

mkdir -p "app/src/main/res/layout"
cat > "app/src/main/res/layout/activity_main.xml" << 'ROVER_EOF'
<?xml version="1.0" encoding="utf-8"?>
<!--
    activity_main.xml — Animal-Avoidance Rover
    ============================================
    Full-screen camera preview with:
      - A custom overlay view for drawing bounding boxes over detections
      - A status bar at the top showing detection + action + BLE connection state
      - Optional manual override buttons at the bottom (stop / resume)

    The DetectionOverlayView referenced here (com.example.animalrover.DetectionOverlayView)
    is a custom View you'd implement separately — draw() loop takes the latest
    bounding boxes + labels and draws rectangles/text over the preview. Sketch
    below the layout shows its minimal shape.
-->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <!-- Live camera feed -->
    <androidx.camera.view.PreviewView
        android:id="@+id/previewView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Bounding box overlay, drawn on top of the camera preview -->
    <com.example.animalrover.DetectionOverlayView
        android:id="@+id/overlayView"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Status bar: detection label, current action, BLE connection state -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="top"
        android:orientation="vertical"
        android:background="#88000000"
        android:padding="12dp">

        <TextView
            android:id="@+id/statusText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="No animal detected"
            android:textColor="#FFFFFF"
            android:textSize="16sp" />

        <TextView
            android:id="@+id/bleStatusText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="BLE: disconnected"
            android:textColor="#FFAA00"
            android:textSize="14sp" />
    </LinearLayout>

    <!-- Manual override controls -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="horizontal"
        android:background="#88000000"
        android:padding="12dp"
        android:gravity="center">

        <Button
            android:id="@+id/emergencyStopButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="16dp"
            android:text="EMERGENCY STOP"
            android:backgroundTint="#CC0000"
            android:textColor="#FFFFFF" />

        <Button
            android:id="@+id/resumeButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="RESUME AUTO"
            android:backgroundTint="#008800"
            android:textColor="#FFFFFF" />
    </LinearLayout>

</FrameLayout>

<!--
    Minimal DetectionOverlayView.kt sketch (separate file, not XML):

    class DetectionOverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
        private var boxes: List<RectF> = emptyList()
        private var labels: List<String> = emptyList()
        private val paint = Paint().apply {
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
            postInvalidate() // trigger redraw on the UI thread
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            boxes.forEachIndexed { i, box ->
                canvas.drawRect(box, paint)
                canvas.drawText(labels.getOrElse(i) { "" }, box.left, box.top - 10f, textPaint)
            }
        }
    }

    Call overlayView.updateDetections(...) from processFrame() in MainActivity
    after each detection pass, converting ML Kit's boundingBox (Rect, in image
    coordinates) into view coordinates that account for the preview's scale
    and rotation.
-->
ROVER_EOF
echo "Created app/src/main/res/layout/activity_main.xml"

mkdir -p ".github/workflows"
cat > ".github/workflows/build.yml" << 'ROVER_EOF'
name: Build Rover APK

on:
  push:
    branches: [ main ]
  workflow_dispatch: # lets you trigger a build manually from the Actions tab

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK as build artifact
        uses: actions/upload-artifact@v4
        with:
          name: animal-rover-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
ROVER_EOF
echo "Created .github/workflows/build.yml"

echo "Done. Review app/build.gradle to make sure the package name matches com.example.animalrover,"
echo "and add the CameraX / ML Kit dependencies + manifest permissions noted in the file comments."
