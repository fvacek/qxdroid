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

    private val siReaderWriter: SiReaderWriter = SiReaderWriter(
        sendSiFrame = { frame -> serialPortManager.sendDataFrame(frame) },
        onCardRead = { card ->
            readLog.add(ReadOutObject.CardReadObject(card))
        }
    )

    private val serialPortManager: SerialPortManager = SerialPortManager(
        onRawData = { data -> hexLog.add(bytesToHex(data)) },
        onDataFrame = { frame ->
            siReaderWriter.onDataFrame(frame)
            logDataFrame(frame)
        },
        onError = { e ->
            connectionStatus = ConnectionStatus.Disconnected("Error: ${e.message}")
        }
    )

    fun setStatus(status: ConnectionStatus) {
        connectionStatus = status
    }

    fun connect(port: UsbSerialPort, usbConnection: android.hardware.usb.UsbDeviceConnection) {
        try {
            port.open(usbConnection)
            port.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialPortManager.start(port)
            connectionStatus = ConnectionStatus.Connected
        } catch (e: IOException) {
            connectionStatus = ConnectionStatus.Disconnected("Error: ${e.message}")
            serialPortManager.stop()
        }
    }

    fun disconnect() {
        serialPortManager.stop()
        if (connectionStatus !is ConnectionStatus.Disconnected) {
            connectionStatus = ConnectionStatus.Disconnected("Disconnected")
        }
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
        serialPortManager.stop()
    }
}
