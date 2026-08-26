package org.qxqx.qxdroid.bt


import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback

import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.bytesToHex
import org.qxqx.qxdroid.si.SiReadOut
import timber.log.Timber
import java.util.UUID

class BtleReaderManager(
    context: Context,

    private val onConnectionStatus: (ConnectionStatus) -> Unit,
    private val onRawData: (ByteArray) -> Unit,
    private val onReadOut: (SiReadOut) -> Unit,
) {
    data class ReaderDevice(val address: String, val name: String?) {
        val displayName: String get() = name ?: "Reader BT ($address)"
    }

    private val appContext = context.applicationContext
    private val bluetoothAdapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanResultCount = 0
    private var readerResultCount = 0
    private val decoder = BtleSiProtocolDecoder(onReadOut)
    private var gatt: BluetoothGatt? = null
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable {
        if (isScanning) {
            Timber.w("Reader BT BLE scan timed out after 20 seconds; received $scanResultCount advertisement result(s)")
            stopScan()
            onConnectionStatus(
                ConnectionStatus.Disconnected(
                    when {
                        scanResultCount == 0 ->
                            "No BLE advertisements received; ensure Reader BT advertising is enabled"
                        readerResultCount == 0 ->
                            "No Reader BT found in $scanResultCount BLE advertisement result(s)"
                        else ->
                            "Scan complete: found $readerResultCount Reader BT advertisement(s)"
                    }
                )
            )
        }
    }
    private val pendingNotificationCharacteristics = ArrayDeque<BluetoothGattCharacteristic>()

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) {
            Timber.d("BLE scan requested while a scan is already running")
            return
        }
        Timber.i("Starting Reader BT BLE scan: adapter=${bluetoothAdapter != null}, enabled=${bluetoothAdapter?.isEnabled}")
        if (!isBluetoothEnabled) {
            Timber.w("Cannot start Reader BT BLE scan because Bluetooth is disabled")
            onConnectionStatus(ConnectionStatus.Disconnected("Bluetooth is disabled"))
            return
        }
        val bluetoothScanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Timber.e("Cannot start BLE scan: Bluetooth adapter or scanner is unavailable")
            onConnectionStatus(ConnectionStatus.Disconnected("Bluetooth is unavailable or disabled"))
            return
        }
        scanResultCount = 0
        readerResultCount = 0
        isScanning = true
        onConnectionStatus(ConnectionStatus.Connecting("scanning for Reader BT"))
        try {
            Timber.d("BLE scan target service UUID=$READER_SETTINGS_SERVICE_UUID; using unfiltered scan for compatibility")
            // Do not apply a hardware scan filter here: several Android BLE stacks expose
            // service UUIDs only in the scan response. Filter Reader BT candidates in the callback.
            bluetoothScanner.startScan(
                emptyList(),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            Timber.i("Reader BT BLE scan started; will stop automatically after 20 seconds")
            mainHandler.postDelayed(scanTimeout, SCAN_DURATION_MS)
        } catch (e: Exception) {
            isScanning = false
            Timber.e(e, "Exception while starting Reader BT BLE scan")
            onConnectionStatus(ConnectionStatus.Disconnected("Cannot start Bluetooth scan: ${e.message}"))
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        mainHandler.removeCallbacks(scanTimeout)
        Timber.i("Stopping Reader BT BLE scan after $scanResultCount result(s)")
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Timber.w(e, "Exception while stopping Reader BT BLE scan")
        }
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    fun connect(device: ReaderDevice) {
        val bluetoothDevice = bluetoothAdapter?.getRemoteDevice(device.address) ?: run {
            onConnectionStatus(ConnectionStatus.Disconnected("Bluetooth is unavailable or disabled"))
            return
        }
        stopScan()
        disconnect()
        onConnectionStatus(ConnectionStatus.Connecting(device.displayName))
        gatt = bluetoothDevice.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        pendingNotificationCharacteristics.clear()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // A scan result is emitted for every received advertisement. Ignore callbacks
            // that were already queued when the scan was stopped for a matching reader.
            if (!isScanning) return

            scanResultCount++
            val device = result.device
            val record = result.scanRecord
            // Timber.d(
            //     "BLE result #$scanResultCount callbackType=$callbackType address=${device.address} " +
            //         "name=${device.name} rssi=${result.rssi} " +
            //         "services=${record?.serviceUuids?.joinToString() ?: "none"} " +
            //         "data=${record?.bytes?.let(::bytesToHex) ?: "none"}"
            // )
            val advertisedName = record?.deviceName ?: device.name
            if (advertisedName == TARGET_READER_NAME) {
                readerResultCount++
                val reader = ReaderDevice(device.address, advertisedName)
                Timber.i(
                    "Target Reader BT found; connecting immediately " +
                        "address=${reader.address} name=${reader.name}"
                )
                connect(reader)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Timber.e("Reader BT BLE scan failed: errorCode=$errorCode")
            onConnectionStatus(ConnectionStatus.Disconnected("Bluetooth scan failed ($errorCode)"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                onConnectionStatus(ConnectionStatus.Disconnected("Bluetooth connection failed ($status)"))
                gatt.close()
                if (this@BtleReaderManager.gatt === gatt) this@BtleReaderManager.gatt = null
                return
            }
            Timber.i("Reader BT connected; requesting MTU $REQUESTED_MTU")
            onConnectionStatus(ConnectionStatus.Connecting("negotiating Reader BT MTU"))
            if (!gatt.requestMtu(REQUESTED_MTU)) {
                Timber.w("Reader BT MTU request was not accepted; using the default MTU")
                discoverReaderServices(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Reader BT MTU negotiated: $mtu")
            } else {
                Timber.w("Reader BT MTU negotiation failed with status=$status; using the default MTU")
            }
            discoverReaderServices(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionStatus(ConnectionStatus.Disconnected("Service discovery failed ($status)"))
                return
            }
            val readoutService = gatt.getService(CARD_READOUT_SERVICE_UUID)
            Timber.i("Reader BT services discovered; readoutService=$readoutService")
            val characteristics = listOf(CARD_STATE_UUID, CARD_DATA_UUID).mapNotNull { uuid ->
                gatt.getService(CARD_READOUT_SERVICE_UUID)?.getCharacteristic(uuid)
            }
            Timber.i("Reader BT notification characteristics=${characteristics.map { it.uuid }}")
            if (characteristics.size != 2) {
                onConnectionStatus(ConnectionStatus.Disconnected("Reader BT readout service is unavailable"))
                return
            }
            pendingNotificationCharacteristics.addAll(characteristics)
            enableNextNotification(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Timber.i("Reader BT notification descriptor write: descriptorUuid=${descriptor.uuid}, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionStatus(ConnectionStatus.Disconnected("Notification setup failed ($status)"))
                return
            }
            enableNextNotification(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Some Android Bluetooth stacks still dispatch notifications through this callback.
            handleNotification(characteristic, characteristic.value)
        }

        private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Timber.i("Reader BT notification received: uuid=${characteristic.uuid}, ${value.size} bytes=${bytesToHex(value)}")
            onRawData(value)
            decoder.onNotification(characteristic.uuid, value)
        }

    }

    @SuppressLint("MissingPermission")
    private fun discoverReaderServices(gatt: BluetoothGatt) {
        Timber.i("Discovering Reader BT GATT services")
        onConnectionStatus(ConnectionStatus.Connecting("discovering Reader BT services"))
        if (!gatt.discoverServices()) {
            Timber.e("Reader BT discoverServices() was not accepted")
            onConnectionStatus(ConnectionStatus.Disconnected("Cannot discover Reader BT services"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(gatt: BluetoothGatt) {
        val characteristic = pendingNotificationCharacteristics.removeFirstOrNull()
        if (characteristic == null) {
            Timber.i("Reader BT notification setup completed")
            onConnectionStatus(ConnectionStatus.Connected)
            return
        }
        Timber.i("Enabling Reader BT notification: characteristic=${characteristic.uuid}")
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
        if (descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            onConnectionStatus(ConnectionStatus.Disconnected("Cannot subscribe to Reader BT notifications"))
            return
        }
        val writeStatus = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        Timber.i("Reader BT notification descriptor write queued: characteristic=${characteristic.uuid}, status=$writeStatus")
        if (writeStatus != BluetoothStatusCodes.SUCCESS) {
            onConnectionStatus(ConnectionStatus.Disconnected("Cannot enable Reader BT notifications"))
        }
    }

    companion object {
        private const val TARGET_READER_NAME = "Reader BT 1000017"
        val READER_SETTINGS_SERVICE_UUID: UUID = UUID.fromString("bd510001-6aec-4628-a146-f3e95bc49e62")
        private val CARD_READOUT_SERVICE_UUID: UUID = UUID.fromString("bd510011-6aec-4628-a146-f3e95bc49e62")
        private val CARD_STATE_UUID: UUID = UUID.fromString("bd510012-6aec-4628-a146-f3e95bc49e62")
        private val CARD_DATA_UUID: UUID = UUID.fromString("bd510013-6aec-4628-a146-f3e95bc49e62")
        private val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_DURATION_MS = 20_000L
        private const val REQUESTED_MTU = 517
    }
}
