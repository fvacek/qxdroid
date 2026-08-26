package org.qxqx.qxdroid.bt

import android.app.Application

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.QxService
import org.qxqx.qxdroid.bytesToHex
import org.qxqx.qxdroid.si.SiReadOut

class BtleReaderViewModel(application: Application) : AndroidViewModel(application) {

    val readOutLog = mutableStateListOf<SiReadOut>()
    val hexLog = mutableStateListOf<String>()

    var connectionStatus by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected"))
        private set

    var isScanning by mutableStateOf(false)
        private set

    val isBluetoothEnabled: Boolean
        get() = manager.isBluetoothEnabled

    private var qxService: QxService? = null

    fun setService(service: QxService) {
        qxService = service
    }

    private val manager = BtleReaderManager(
        context = application.applicationContext,

        onConnectionStatus = { status ->
            connectionStatus = status
            isScanning = status is ConnectionStatus.Connecting &&
                status.progress == "scanning for Reader BT"
        },
        onRawData = { data -> hexLog += bytesToHex(data) },
        onReadOut = { readOut ->
            readOutLog += readOut
            qxService?.publishReadOut(readOut)
        },
    )

    fun startScan() {
        isScanning = true
        manager.startScan()
    }

    fun stopScan() {
        manager.stopScan()
        isScanning = false
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
