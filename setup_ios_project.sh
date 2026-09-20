#!/usr/bin/env bash
# Sets up the iOS project scaffold (XcodeGen spec + Swift source files + CI workflow).
# Run this from the ROOT of your repo (same repo as the android/ folder, or a fresh one).
set -e

mkdir -p "ios"
cat > "ios/project.yml" << 'ROVER_EOF'
name: AnimalRover
options:
  bundleIdPrefix: com.example

targets:
  AnimalRover:
    type: application
    platform: iOS
    deploymentTarget: "16.0"
    sources:
      - path: Sources
    info:
      path: Info.plist
      properties:
        UILaunchScreen: {}
        NSCameraUsageDescription: "Used to detect animals in the rover's path."
        NSBluetoothAlwaysUsageDescription: "Used to connect to the rover's motor controller."
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.example.animalrover
        CODE_SIGN_STYLE: Automatic
        SWIFT_VERSION: "5.0"
ROVER_EOF
echo "Created ios/project.yml"

mkdir -p "ios/Sources"
cat > "ios/Sources/AnimalRoverApp.swift" << 'ROVER_EOF'
import SwiftUI

@main
struct AnimalRoverApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
ROVER_EOF
echo "Created ios/Sources/AnimalRoverApp.swift"

mkdir -p "ios/Sources"
cat > "ios/Sources/ContentView.swift" << 'ROVER_EOF'
/*
 * ContentView.swift — Animal-Avoidance Rover (iOS)
 * ===================================================
 *
 * Ties together: AVFoundation camera capture -> AnimalDetector (Vision)
 * -> decision logic -> BLEController (sends command to the ESP32).
 *
 * Add to Info.plist:
 *   <key>NSCameraUsageDescription</key>
 *   <string>Used to detect animals in the rover's path.</string>
 */

import SwiftUI
import AVFoundation

struct ContentView: View {
    @StateObject private var camera = CameraController()
    @StateObject private var ble = BLEController()

    var body: some View {
        ZStack {
            CameraPreview(session: camera.session)
                .ignoresSafeArea()

            VStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(camera.statusMessage)
                        .foregroundColor(.white)
                    Text(ble.statusMessage)
                        .foregroundColor(.orange)
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.black.opacity(0.5))

                Spacer()

                HStack(spacing: 16) {
                    Button("EMERGENCY STOP") {
                        camera.manualOverride = true
                        ble.sendCommand("stop")
                    }
                    .padding()
                    .background(Color.red)
                    .foregroundColor(.white)
                    .cornerRadius(8)

                    Button("RESUME AUTO") {
                        camera.manualOverride = false
                    }
                    .padding()
                    .background(Color.green)
                    .foregroundColor(.white)
                    .cornerRadius(8)
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color.black.opacity(0.5))
            }
        }
        .onAppear {
            camera.ble = ble
            camera.start()
            ble.startScan()
        }
    }
}

// --- Camera capture + per-frame detection loop -----------------------------
class CameraController: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {

    let session = AVCaptureSession()
    private let detector = AnimalDetector()
    private let smoother = ActionSmoother()
    private let videoQueue = DispatchQueue(label: "camera.frame.queue")

    var ble: BLEController?
    var manualOverride: Bool = false

    @Published var statusMessage: String = "No animal detected"

    func start() {
        session.beginConfiguration()
        session.sessionPreset = .high

        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else {
            statusMessage = "Camera unavailable"
            return
        }
        if session.canAddInput(input) {
            session.addInput(input)
        }

        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: videoQueue)
        if session.canAddOutput(output) {
            session.addOutput(output)
        }

        session.commitConfiguration()

        DispatchQueue.global(qos: .userInitiated).async {
            self.session.startRunning()
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard !manualOverride, // skip auto logic while manually stopped
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            return
        }

        detector.detectAnimals(in: pixelBuffer) { [weak self] detections in
            guard let self = self else { return }

            let rawAction = self.detector.decideAction(for: detections)
            let smoothedAction = self.smoother.update(rawAction)

            DispatchQueue.main.async {
                if let closest = detections.first {
                    self.statusMessage = "\(closest.label) detected -> \(smoothedAction)"
                } else {
                    self.statusMessage = "No animal detected"
                }
            }

            self.ble?.sendCommand(smoothedAction)
        }
    }
}

// --- UIViewRepresentable wrapper for the camera preview layer ---------------
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = UIScreen.main.bounds
        view.layer.addSublayer(previewLayer)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        if let layer = uiView.layer.sublayers?.first as? AVCaptureVideoPreviewLayer {
            layer.frame = uiView.bounds
        }
    }
}
ROVER_EOF
echo "Created ios/Sources/ContentView.swift"

mkdir -p "ios/Sources"
cat > "ios/Sources/BLEController.swift" << 'ROVER_EOF'
/*
 * BLEController.swift — Animal-Avoidance Rover (iOS)
 * =====================================================
 *
 * Talks to the SAME ESP32 device as the Android app — same service UUID,
 * same characteristic UUID, same single-character command protocol
 * ('S' stop, 'F' forward, 'f' forward_slow, 'L' turn_left, 'R' turn_right).
 * No changes needed on the ESP32 side at all.
 *
 * Uses Apple's CoreBluetooth framework (the iOS equivalent of Android's
 * BluetoothGatt / BluetoothLeScanner).
 *
 * Add to Info.plist:
 *   <key>NSBluetoothAlwaysUsageDescription</key>
 *   <string>Used to connect to the rover's motor controller.</string>
 */

import Foundation
import CoreBluetooth

class BLEController: NSObject, ObservableObject {

    // Must match the UUIDs in esp32_motor_controller.ino exactly
    static let serviceUUID = CBUUID(string: "4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    static let characteristicUUID = CBUUID(string: "beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private let targetDeviceName = "AnimalAvoidanceRover"

    @Published var isConnected: Bool = false
    @Published var statusMessage: String = "BLE: disconnected"

    private var centralManager: CBCentralManager!
    private var roverPeripheral: CBPeripheral?
    private var commandCharacteristic: CBCharacteristic?

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    func startScan() {
        guard centralManager.state == .poweredOn else {
            statusMessage = "BLE: Bluetooth is off"
            return
        }
        statusMessage = "BLE: scanning..."
        centralManager.scanForPeripherals(
            withServices: [BLEController.serviceUUID],
            options: nil
        )
    }

    func stopScan() {
        centralManager.stopScan()
    }

    func disconnect() {
        if let peripheral = roverPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
    }

    // --- Sending commands ---------------------------------------------
    // action is one of: "stop", "forward", "forward_slow", "turn_left", "turn_right"
    func sendCommand(_ action: String) {
        guard let characteristic = commandCharacteristic,
              let peripheral = roverPeripheral else {
            return // not connected yet — no-op, same as the Android version
        }

        let code: String
        switch action {
        case "stop": code = "S"
        case "forward": code = "F"
        case "forward_slow": code = "f"
        case "turn_left": code = "L"
        case "turn_right": code = "R"
        default: code = "F"
        }

        if let data = code.data(using: .utf8) {
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
        }
    }
}

// --- CBCentralManagerDelegate: scanning + connection lifecycle -----------
extension BLEController: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            startScan()
        } else {
            statusMessage = "BLE: Bluetooth unavailable"
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // Filter by name to make sure we're connecting to our own rover
        guard peripheral.name == targetDeviceName else { return }

        roverPeripheral = peripheral
        peripheral.delegate = self
        stopScan()
        statusMessage = "BLE: connecting..."
        centralManager.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        statusMessage = "BLE: connected, discovering services..."
        peripheral.discoverServices([BLEController.serviceUUID])
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        isConnected = false
        commandCharacteristic = nil
        statusMessage = "BLE: disconnected"
        // Optional: auto-retry, since a dropped link shouldn't leave the
        // rover silently stuck with no way to receive commands.
        startScan()
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        statusMessage = "BLE: connection failed"
        startScan()
    }
}

// --- CBPeripheralDelegate: service/characteristic discovery ---------------
extension BLEController: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == BLEController.serviceUUID {
            peripheral.discoverCharacteristics([BLEController.characteristicUUID], for: service)
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard let characteristics = service.characteristics else { return }
        for characteristic in characteristics where characteristic.uuid == BLEController.characteristicUUID {
            commandCharacteristic = characteristic
            isConnected = true
            statusMessage = "BLE: connected"
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error = error {
            print("BLE write failed: \(error.localizedDescription)")
        }
    }
}
ROVER_EOF
echo "Created ios/Sources/BLEController.swift"

mkdir -p "ios/Sources"
cat > "ios/Sources/AnimalDetector.swift" << 'ROVER_EOF'
/*
 * AnimalDetector.swift — Animal-Avoidance Rover (iOS)
 * ======================================================
 *
 * Uses Apple's Vision framework built-in animal recognition
 * (VNRecognizeAnimalsRequest), which detects cats and dogs out of the box
 * with zero model setup — no downloading or bundling a Core ML model needed.
 *
 * LIMITATION: VNRecognizeAnimalsRequest only recognizes cats and dogs.
 * If you need broader species (birds, horses, farm animals, wildlife, etc.
 * matching the COCO classes the Android/Pi versions use), swap this out for
 * a custom Core ML object detection model (e.g. a converted YOLOv8 or SSD
 * MobileNet COCO model) using VNCoreMLRequest instead. The decision logic
 * below is identical either way — only how detections are produced changes.
 */

import Foundation
import Vision
import CoreGraphics

struct AnimalDetection {
    let label: String
    let confidence: Float
    let boundingBox: CGRect // normalized 0.0-1.0, Vision's coordinate space (origin bottom-left)
}

class AnimalDetector {

    private let confidenceThreshold: Float = 0.5

    // Same thresholds as the Android/Pi versions, kept identical on purpose
    // so all three platforms behave the same way given the same footage.
    private let centerZone: ClosedRange<CGFloat> = 0.35...0.65
    private let closeHeightRatio: CGFloat = 0.5
    private let medHeightRatio: CGFloat = 0.25

    func detectAnimals(
        in pixelBuffer: CVPixelBuffer,
        completion: @escaping ([AnimalDetection]) -> Void
    ) {
        let request = VNRecognizeAnimalsRequest { request, error in
            guard error == nil,
                  let results = request.results as? [VNRecognizedObjectObservation] else {
                completion([])
                return
            }

            let detections: [AnimalDetection] = results.compactMap { observation in
                guard let topLabel = observation.labels.first,
                      topLabel.confidence >= self.confidenceThreshold else {
                    return nil
                }
                return AnimalDetection(
                    label: topLabel.identifier, // "Cat" or "Dog"
                    confidence: topLabel.confidence,
                    boundingBox: observation.boundingBox
                )
            }
            completion(detections)
        }

        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, options: [:])
        DispatchQueue.global(qos: .userInitiated).async {
            try? handler.perform([request])
        }
    }

    // --- Decision logic (identical shape to Android/Pi versions) -----------
    func decideAction(for detections: [AnimalDetection]) -> String {
        guard !detections.isEmpty else { return "forward" }

        // Pick the detection with the largest bounding box height (closest)
        guard let closest = detections.max(by: { $0.boundingBox.height < $1.boundingBox.height }) else {
            return "forward"
        }

        let box = closest.boundingBox
        let heightRatio = box.height
        let centerX = box.midX
        let inPath = centerZone.contains(centerX)

        if heightRatio >= closeHeightRatio && inPath {
            return "stop"
        } else if heightRatio >= medHeightRatio && inPath {
            return centerX >= 0.5 ? "turn_left" : "turn_right"
        } else if inPath {
            return "forward_slow"
        } else {
            return "forward"
        }
    }
}

// --- Smoothing: require an action to persist across recent frames ---------
class ActionSmoother {
    private let window: Int
    private var history: [String] = []

    init(window: Int = 3) {
        self.window = window
    }

    func update(_ action: String) -> String {
        history.append(action)
        if history.count > window {
            history.removeFirst()
        }

        let stopCount = history.filter { $0 == "stop" }.count
        if stopCount >= max(1, window - 1) {
            return "stop"
        }

        let matchCount = history.filter { $0 == action }.count
        if matchCount >= (window / 2 + 1) {
            return action
        }

        return "forward"
    }
}
ROVER_EOF
echo "Created ios/Sources/AnimalDetector.swift"

mkdir -p ".github/workflows"
cat > ".github/workflows/build-ios.yml" << 'ROVER_EOF'
name: Build Rover iOS App

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: macos-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Install XcodeGen
        run: brew install xcodegen

      - name: Generate Xcode project
        run: |
          cd ios
          xcodegen generate

      - name: Build for iOS Simulator (no code signing required)
        run: |
          cd ios
          xcodebuild -project AnimalRover.xcodeproj \
            -scheme AnimalRover \
            -sdk iphonesimulator \
            -destination 'platform=iOS Simulator,name=iPhone 15' \
            build
ROVER_EOF
echo "Created .github/workflows/build-ios.yml"

echo "Done. Commit and push -- GitHub Actions will generate the Xcode project via XcodeGen"
echo "and build it on a macOS runner. This verifies the app compiles for the simulator;"
echo "installing on a real iPhone requires code signing setup (Apple ID or paid dev account),"
echo "which is a separate step beyond this script."
