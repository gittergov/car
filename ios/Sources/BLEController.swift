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
