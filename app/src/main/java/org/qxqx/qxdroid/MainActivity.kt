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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
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
    private var receivedData by mutableStateOf("No data received yet")
    private var connectionStatus by mutableStateOf("Disconnected")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val usbDevice: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
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
                QxDroidApp(receivedData, connectionStatus)
            }
        }
        handleIntent(intent)
    }

    override fun onNewData(data: ByteArray) {
        runOnUiThread {
            receivedData = String(data)
        }
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
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
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
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
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
fun QxDroidApp(data: String, connectionStatus: String) {
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
            if(currentDestination == AppDestinations.HOME) {
                Column(modifier = Modifier.padding(innerPadding)) {
                    Text(text = "Status: $connectionStatus")
                    Greeting(
                        name = data,
                    )
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
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    QxDroidTheme {
        QxDroidApp(data = "Preview Data", connectionStatus = "Disconnected")
    }
}
