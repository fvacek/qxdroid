package org.qxqx.qxdroid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.qxqx.qxdroid.ui.theme.QxDroidTheme
import java.io.IOException

class MainActivity : ComponentActivity(), SerialInputOutputManager.Listener {

    private var deviceId: Int = 0
    private var portNum: Int = 0
    private var serialInputOutputManager: SerialInputOutputManager? = null
    private lateinit var usbPermissionReceiver: BroadcastReceiver
    private var usbSerialPort: UsbSerialPort? = null
    private var withIoManager: Boolean = false
    private val protocolLog = mutableStateListOf<String>()
    private val hexLog = mutableStateListOf<String>()
    private var connectionStatus by mutableStateOf("Disconnected")
    private val dataBuffer = mutableListOf<Byte>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val usbDevice: UsbDevice? =
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (usbDevice != null) {
                                //permission granted
                                connect(usbDevice)
                            }
                        } else {
                            connectionStatus = "Permission denied"
                        }
                    }
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            QxDroidTheme {
                QxDroidApp(hexLog, protocolLog, connectionStatus) { clearLog() }
            }
        }
        handleIntent(intent)
    }

    private fun clearLog() {
        protocolLog.clear()
        hexLog.clear()
    }

    // --- Protocol Parsing Logic ---

    override fun onNewData(data: ByteArray) {
        Log.d(TAG, "onNewData: Received ${data.size} bytes: ${bytesToHex(data)}")
        runOnUiThread { hexLog.add(bytesToHex(data)) }
        dataBuffer.addAll(data.toList())
        processDataBuffer()
    }

    private fun processDataBuffer() {
        Log.d(TAG, "processDataBuffer: Starting with buffer size ${dataBuffer.size}")
        while (true) {
            // Find the start of a frame
            val stxIndex = dataBuffer.indexOf(STX)
            if (stxIndex == -1) {
                Log.d(TAG, "processDataBuffer: No STX found, waiting for more data.")
                return // No STX found, wait for more data
            }
            Log.d(TAG, "processDataBuffer: Found STX at index $stxIndex")

            // Discard any data before STX
            if (stxIndex > 0) {
                Log.d(TAG, "processDataBuffer: Discarding $stxIndex bytes before STX.")
                dataBuffer.subList(0, stxIndex).clear()
            }

            // Need at least 3 bytes for STX, command, and length
            if (dataBuffer.size < 3) {
                Log.d(TAG, "processDataBuffer: Buffer size ${dataBuffer.size} is too small for a header, waiting.")
                return
            }

            // Check if it's the extended protocol (command >= 0x80)
            val command = dataBuffer[1]
            if (command.toInt() and 0xFF < 0x80) {
                logMessage("Unsupported frame (CMD < 0x80): ${bytesToHex(byteArrayOf(command))}")
                Log.w(TAG, "processDataBuffer: Unsupported frame (CMD < 0x80), discarding STX.")
                dataBuffer.removeAt(0) // Discard STX and continue
                continue
            }

            val dataLength = dataBuffer[2].toInt() and 0xFF
            val frameLength = 1 + 1 + 1 + dataLength + 2 + 1 // STX, CMD, LEN, DATA, CRC, ETX
            Log.d(TAG, "processDataBuffer: Expected frame length is $frameLength (data length: $dataLength)")

            if (dataBuffer.size < frameLength) {
                Log.d(TAG, "processDataBuffer: Buffer size ${dataBuffer.size} is too small for full frame, waiting.")
                return // Not enough data for the full frame, wait
            }

            // Check for ETX at the end of the frame
            if (dataBuffer[frameLength - 1] != ETX) {
                logMessage("Malformed frame (bad ETX). Discarding STX.")
                Log.w(TAG, "processDataBuffer: Malformed frame (bad ETX). Discarding STX.")
                dataBuffer.removeAt(0) // Discard STX and retry
                continue
            }

            // If we are here, we have a complete frame
            Log.d(TAG, "processDataBuffer: Found complete frame of length $frameLength.")
            val frame = dataBuffer.take(frameLength).toByteArray()
            parseAndLogFrame(frame)

            // Remove the processed frame from the buffer
            Log.d(TAG, "processDataBuffer: Removing processed frame from buffer.")
            dataBuffer.subList(0, frameLength).clear()
        }
    }

    private fun parseAndLogFrame(frame: ByteArray) {
        val command = frame[1].toInt() and 0xFF
        val length = frame[2].toInt() and 0xFF
        val data = frame.copyOfRange(3, 3 + length)
        val receivedCrcBytes = frame.copyOfRange(3 + length, 5 + length)

        val dataForCrc = frame.copyOfRange(1, 3 + length)
        val calculatedCrc = crc16(dataForCrc)
        val receivedCrc = ((receivedCrcBytes[0].toInt() and 0xFF) shl 8) or (receivedCrcBytes[1].toInt() and 0xFF)

        val crcStatus = if (calculatedCrc == receivedCrc) "OK" else "FAIL"
        val logMessage = "CMD: ${bytesToHex(byteArrayOf(command.toByte()))} | LEN: $length | DATA: ${bytesToHex(data)} | CRC: ${bytesToHex(receivedCrcBytes)} ($crcStatus)"
        Log.i(TAG, "parseAndLogFrame: $logMessage")
        logMessage(logMessage)
    }

    private fun logMessage(message: String) {
        runOnUiThread {
            if (protocolLog.isNotEmpty() && protocolLog.last() == "No data received yet") {
                protocolLog.clear()
            }
            protocolLog.add(message)
        }
    }

    /**
     * Calculates the CRC-16-CCITT checksum for the given data.
     * This is a standard implementation, assuming the polynomial 0x1021 and initial value 0xFFFF.
     */
    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF shl 8)
            for (i in 0..7) {
                crc = if ((crc and 0x8000) != 0) {
                    (crc shl 1) xor 0x1021
                } else {
                    crc shl 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = "0123456789ABCDEF"[v ushr 4]
            hexChars[j * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
        }
        return String(hexChars)
    }

    // --- USB Connection Logic ---

    override fun onRunError(e: Exception?) {
        connectionStatus = "Error: ${e?.message}"
        disconnect()
    }

    private fun connect(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        connectionStatus = "Connecting..."
        var driver: UsbSerialDriver? = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            // If default prober fails, and we know it's a Silicon Labs device,
            // we can try to instantiate the driver manually.
            if (device.vendorId == 0x10C4) {
                connectionStatus = "VID 10c4 detected, trying manual driver..."
                driver = Cp21xxSerialDriver(device)
            } else {
                connectionStatus = "Disconnected (no driver found)"
                return
            }
        }

        if (driver.ports.isEmpty()) {
            connectionStatus = "Disconnected (no ports)"
            return
        }
        usbSerialPort = driver.ports[portNum]
        val usbConnection: UsbDeviceConnection? = usbManager.openDevice(driver.device)
        if (usbConnection == null && !usbManager.hasPermission(driver.device)) {
            connectionStatus = "Disconnected (permission pending)"
            val usbPermissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(driver.device, usbPermissionIntent)
            return
        }

        if (usbConnection == null) {
            connectionStatus = "Disconnected (cannot open device)"
            return
        }

        try {
            usbSerialPort?.open(usbConnection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            if (withIoManager) {
                serialInputOutputManager = SerialInputOutputManager(usbSerialPort, this)
                serialInputOutputManager?.start()
            }
            connectionStatus = "Connected"
            clearLog()
            logMessage("No data received yet")
        } catch (e: IOException) {
            connectionStatus = "Error: ${e.message}"
            disconnect()
        }
    }

    private fun disconnect() {
        serialInputOutputManager?.stop()
        serialInputOutputManager = null
        try {
            usbSerialPort?.close()
        } catch (ignored: IOException) {
        }
        usbSerialPort = null
        if (connectionStatus != "Permission denied" && !connectionStatus.startsWith("Error")) {
            connectionStatus = "Disconnected"
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? =
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            device?.let {
                this.deviceId = it.deviceId
                portNum = 0
                withIoManager = true
                connect(it)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }


    override fun onResume() {
        super.onResume()
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)

        if (connectionStatus == "Disconnected") {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            // Find the device by vendor ID
            val device = usbManager.deviceList.values.find { it.vendorId == 0x10C4 }
            device?.let {
                this.deviceId = it.deviceId
                portNum = 0
                withIoManager = true
                // Attempt to connect to the found device
                connect(it)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbPermissionReceiver)
        disconnect()
    }


    companion object {
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
        private const val TAG = "MainActivity"
        private const val STX: Byte = 0x02
        private const val ETX: Byte = 0x03
        private const val ACK: Byte = 0x06
        private const val NAK: Byte = 0x15
        private const val DLE: Byte = 0x10
    }
}

@PreviewScreenSizes
@Composable
fun QxDroidApp(
    hexData: List<String> = listOf("DEADBEEF"),
    protocolData: List<String> = listOf("CMD: 80 | LEN: 2 | DATA: 0102 | CRC: 0304 (OK)"),
    connectionStatus: String = "Preview",
    onClearLog: () -> Unit = {}
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            if (currentDestination == AppDestinations.HOME) {
                Column(modifier = Modifier.padding(innerPadding)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusColor = when {
                            connectionStatus == "Connected" -> Color(0, 102, 0)
                            connectionStatus.startsWith("Error") ||
                                    connectionStatus.startsWith("Disconnected (") ||
                                    connectionStatus == "Permission denied" -> Color.Red
                            else -> Color.Gray
                        }
                        Text(
                            text = "Status: $connectionStatus",
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(statusColor)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Button(onClick = onClearLog) {
                            Text(text = "Clear Log")
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                        ) {
                            Text(
                                "Hex Data",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider()
                            DataLog(log = hexData)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                        ) {
                            Text(
                                "Parsed Protocol",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider()
                            DataLog(log = protocolData)
                        }
                    }
                }
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    FAVORITES("Favorites", Icons.Default.Favorite),
    PROFILE("Profile", Icons.Default.AccountBox),
}

@Composable
fun DataLog(log: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(log) { line ->
            Text(text = line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun QxDroidAppPreview() {
    QxDroidTheme {
        QxDroidApp()
    }
}
