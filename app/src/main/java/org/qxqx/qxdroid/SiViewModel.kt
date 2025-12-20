package org.qxqx.qxdroid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.hoho.android.usbserial.driver.UsbSerialPort
import org.qxqx.qxdroid.si.*
import java.io.IOException

class SiViewModel : ViewModel() {
    val readLog = mutableStateListOf<ReadOutObject>()
    val hexLog = mutableStateListOf<String>()
    var connectionStatus by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected"))
        private set

    private var usbConnection: android.hardware.usb.UsbDeviceConnection? = null
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
            readLog.add(ReadOutObject.CardReadObject(card))
        }
    )

    fun setStatus(status: ConnectionStatus) {
        connectionStatus = status
    }

    fun connect(port: UsbSerialPort, usbConnection1: android.hardware.usb.UsbDeviceConnection) {
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
        serialPortManager.stop()
        usbSerialPort?.close()
        usbConnection?.close()
        connectionStatus = ConnectionStatus.Disconnected(error?:"Disconnected")
    }

    fun clearLogs() {
        readLog.clear()
        hexLog.clear()
    }

    private fun logDataFrame(dataFrame: SiDataFrame) {
        val cmd = toSiRecCommand(dataFrame)
        if (cmd is SiCardDetected || cmd is SiCardRemoved) {
            readLog.add(ReadOutObject.Command(cmd))
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
