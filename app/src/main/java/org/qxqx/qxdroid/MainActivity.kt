package org.qxqx.qxdroid

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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
    private val receivedData = mutableStateListOf<String>()
    private var connectionStatus by mutableStateOf("Disconnected")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val usbDevice: UsbDevice? =
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
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
                QxDroidApp(receivedData, connectionStatus) { clearLog() }
            }
        }
        handleIntent(intent)
    }

    private fun clearLog() {
        receivedData.clear()
    }

    override fun onNewData(data: ByteArray) {
        runOnUiThread {
            if (receivedData.isEmpty() || receivedData.last() == "No data received yet") {
                receivedData.clear()
            }
            receivedData.add(bytesToHex(data))
        }
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
            val flags =
                PendingIntent.FLAG_MUTABLE
            val usbPermissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                flags
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
            receivedData.clear()
            receivedData.add("No data received yet")

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
    }
}

@PreviewScreenSizes
@Composable
fun QxDroidApp(
    data: List<String> = listOf("Sample Log 1", "Sample Log 2"),
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
                            connectionStatus == "Connected" -> Color.Green
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
                    DataLog(log = data)
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
            Text(text = line, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DataLogPreview() {
    QxDroidTheme {
        DataLog(log = listOf("7E0102030405067E", "7E0708090A0B0C7E"))
    }
}
