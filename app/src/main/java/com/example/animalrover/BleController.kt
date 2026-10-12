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
