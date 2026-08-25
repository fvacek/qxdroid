package org.qxqx.qxdroid.bt

import android.app.Application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.bytesToHex
import org.qxqx.qxdroid.si.SiReadOut

class BtleReaderViewModel(application: Application) : AndroidViewModel(application) {
    val devices = mutableStateListOf<BtleReaderManager.ReaderDevice>()
    val readOutLog = mutableStateListOf<SiReadOut>()
    val hexLog = mutableStateListOf<String>()

    var connectionStatus by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected"))
        private set

    private val manager = BtleReaderManager(
        context = application.applicationContext,
        onDeviceFound = { device ->
            if (devices.none { it.address == device.address }) devices += device
        },
        onConnectionStatus = { status -> connectionStatus = status },
        onRawData = { data -> hexLog += bytesToHex(data) },
        onReadOut = { readOut -> readOutLog += readOut },
    )

    fun startScan() {
        devices.clear()
        manager.startScan()
    }

    fun stopScan() = manager.stopScan()

    fun connect(device: BtleReaderManager.ReaderDevice) = manager.connect(device)

    fun disconnect() {
        manager.disconnect()
        connectionStatus = ConnectionStatus.Disconnected("Disconnected")
    }

    fun clearLogs() {
        readOutLog.clear()
        hexLog.clear()
    }

    override fun onCleared() {
        manager.stopScan()
        manager.disconnect()
    }
}
