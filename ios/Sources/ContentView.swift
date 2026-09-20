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
