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
