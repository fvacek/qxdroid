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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import org.qxqx.qxdroid.ConnectionStatus

import org.qxqx.qxdroid.si.SiReadOut

import java.util.UUID

class BtleReaderManager(
    context: Context,
    private val onDeviceFound: (ReaderDevice) -> Unit,
    private val onConnectionStatus: (ConnectionStatus) -> Unit,
    private val onRawData: (ByteArray) -> Unit,
    private val onReadOut: (SiReadOut) -> Unit,
) {
    data class ReaderDevice(val address: String, val name: String?) {
        val displayName: String get() = name ?: "Reader BT ($address)"
    }

    private val appContext = context.applicationContext
    private val bluetoothAdapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    private val decoder = BtleSiProtocolDecoder(onReadOut)
    private var gatt: BluetoothGatt? = null
    private var isScanning = false
    private val pendingNotificationCharacteristics = ArrayDeque<BluetoothGattCharacteristic>()

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (isScanning) return
        val bluetoothScanner = scanner ?: run {
            onConnectionStatus(ConnectionStatus.Disconnected("Bluetooth is unavailable or disabled"))
            return
        }
        isScanning = true
        onConnectionStatus(ConnectionStatus.Connecting("scanning for Reader BT"))
        bluetoothScanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(READER_SETTINGS_SERVICE_UUID)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (isScanning) scanner?.stopScan(scanCallback)
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
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onDeviceFound(ReaderDevice(result.device.address, result.device.name))
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
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
            onConnectionStatus(ConnectionStatus.Connecting("discovering Reader BT services"))
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionStatus(ConnectionStatus.Disconnected("Service discovery failed ($status)"))
                return
            }
            val characteristics = listOf(CARD_STATE_UUID, CARD_DATA_UUID).mapNotNull { uuid ->
                gatt.getService(CARD_READOUT_SERVICE_UUID)?.getCharacteristic(uuid)
            }
            if (characteristics.size != 2) {
                onConnectionStatus(ConnectionStatus.Disconnected("Reader BT readout service is unavailable"))
                return
            }
            pendingNotificationCharacteristics.addAll(characteristics)
            enableNextNotification(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionStatus(ConnectionStatus.Disconnected("Notification setup failed ($status)"))
                return
            }
            enableNextNotification(gatt)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onRawData(value)
            decoder.onNotification(characteristic.uuid, value)
        }

    }

    @SuppressLint("MissingPermission")
    private fun enableNextNotification(gatt: BluetoothGatt) {
        val characteristic = pendingNotificationCharacteristics.removeFirstOrNull()
        if (characteristic == null) {
            onConnectionStatus(ConnectionStatus.Connected)
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID)
        if (descriptor == null || !gatt.setCharacteristicNotification(characteristic, true)) {
            onConnectionStatus(ConnectionStatus.Disconnected("Cannot subscribe to Reader BT notifications"))
            return
        }
        if (gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) {
            onConnectionStatus(ConnectionStatus.Disconnected("Cannot enable Reader BT notifications"))
        }
    }

    companion object {
        val READER_SETTINGS_SERVICE_UUID: UUID = UUID.fromString("bd510001-6aec-4628-a146-f3e95bc49e62")
        private val CARD_READOUT_SERVICE_UUID: UUID = UUID.fromString("bd510011-6aec-4628-a146-f3e95bc49e62")
        private val CARD_STATE_UUID: UUID = UUID.fromString("bd510012-6aec-4628-a146-f3e95bc49e62")
        private val CARD_DATA_UUID: UUID = UUID.fromString("bd510013-6aec-4628-a146-f3e95bc49e62")
        private val CLIENT_CHARACTERISTIC_CONFIGURATION_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
