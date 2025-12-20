package org.qxqx.qxdroid.si

import android.hardware.usb.UsbDeviceConnection
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.bytesToHex
import timber.log.Timber
import java.io.IOException

class SiViewModel : ViewModel() {
    val readOutLog = mutableStateListOf<SiReadOut>()
    private val _readOutEvents = MutableSharedFlow<SiReadOut>()
    val readOutEvents = _readOutEvents.asSharedFlow()
    val hexLog = mutableStateListOf<String>()
    var connectionStatus by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected"))
        private set

    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private val serialPortManager: SerialPortManager = SerialPortManager(
        onRawData = { data -> hexLog.add(bytesToHex(data)) },
        onDataFrame = { frame ->
            siProtocolDecoder.onDataFrame(frame)
            logDataFrame(frame)
        },
        onError = { e ->
            connectionStatus = ConnectionStatus.Disconnected("Error: ${e.message}")
        }
    )

    private val siProtocolDecoder: SiProtocolDecoder = SiProtocolDecoder(
        sendSiFrame = { frame -> serialPortManager.sendDataFrame(frame) },
        onCardRead = { card ->
            val readOut = SiReadOut.Card(card)
            readOutLog.add(readOut)
            // 2. Broadcast the event
            viewModelScope.launch {
                _readOutEvents.emit(readOut)
            }
        }
    )

    fun setStatus(status: ConnectionStatus) {
        connectionStatus = status
    }

    fun connect(port: UsbSerialPort, usbConnection1: UsbDeviceConnection) {
        try {
            usbConnection = usbConnection1
            usbSerialPort = port
            port.open(usbConnection)
            port.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            serialPortManager.start(port)
            connectionStatus = ConnectionStatus.Connected
        } catch (e: IOException) {
            disconnect("Error: ${e.message}")
        }
    }

    fun disconnect(error: String? = null) {
        try {
            serialPortManager.stop()
            usbSerialPort?.close()
            usbConnection?.close()
        } catch (e: IOException) {
            // Log it, but don't crash.
            // "Already closed" is a common state here.
            Timber.w(e, "Serial port close failed")
        } finally {
            connectionStatus = ConnectionStatus.Disconnected(error?:"Disconnected")
        }
    }

    fun clearLogs() {
        readOutLog.clear()
        hexLog.clear()
    }

    private fun logDataFrame(dataFrame: SiDataFrame) {
        val cmd = toSiRecCommand(dataFrame)
        if (cmd is SiCardDetected) {
            readOutLog.add(SiReadOut.CardDetected(cmd))
        }
        else if (cmd is SiCardRemoved) {
            readOutLog.add(SiReadOut.CardRemoved(cmd))
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}