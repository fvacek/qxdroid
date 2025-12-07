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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.si.CardKind
import org.qxqx.qxdroid.si.ReadOutObject
import org.qxqx.qxdroid.si.SerialPortManager
import org.qxqx.qxdroid.si.SiCard
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.SiReader
import org.qxqx.qxdroid.si.toSiRecCommand
import org.qxqx.qxdroid.shv.ShvClient
import org.qxqx.qxdroid.ui.theme.QxDroidTheme
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var deviceId: Int = 0
    private var portNum: Int = 0
    private lateinit var usbPermissionReceiver: BroadcastReceiver
    private var usbSerialPort: UsbSerialPort? = null
    private val readLog = mutableStateListOf<ReadOutObject>()
    private val hexLog = mutableStateListOf<String>()
    private var connectionStatus by mutableStateOf("Disconnected")

    private lateinit var siReader: SiReader
    private lateinit var serialPortManager: SerialPortManager
    private lateinit var shvClient: ShvClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        siReader = SiReader(
            sendSiFrame = { frame -> serialPortManager.sendDataFrame(frame) },
            onCardRead = { card -> logSiCard(card) }
        )
        serialPortManager = SerialPortManager(
            onRawData = { data -> runOnUiThread { hexLog.add(bytesToHex(data)) } },
            onDataFrame = { frame ->
                run {
                    siReader.onDataFrame(frame)
                    logDataFrame(frame)
                }
            },
            onError = { e ->
                runOnUiThread {
                    connectionStatus = "Error: ${e.message}"
                    disconnect()
                }
            },
        )
        
        shvClient = ShvClient()

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
                QxDroidApp(
                    hexLog, 
                    readLog, 
                    connectionStatus, 
                    onClearLog = { clearLog() },
                    onConnectShv = { url ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                shvClient.connect(url)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Connection error", e)
                            }
                        }
                    }
                )
            }
        }
        handleIntent(intent)
    }

    private fun clearLog() {
        readLog.clear()
        hexLog.clear()
    }

    private fun logDataFrame(dataFrame: SiDataFrame) {
        runOnUiThread {
            val cmd = toSiRecCommand(dataFrame)
            if (cmd is SiCardDetected || cmd is SiCardRemoved) {
                readLog.add(ReadOutObject.Command(cmd))
            }
        }
    }

    private fun logSiCard(card: SiCard) {
        //Log.d("MainActivity", "SI card read: ${card}.")
        runOnUiThread {
            readLog.add(ReadOutObject.CardReadObject(card))
        }
    }

    // --- USB Connection Logic ---

    private fun connect(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
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
            usbSerialPort?.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort?.let { serialPortManager.start(it) }
            connectionStatus = "Connected"
            clearLog()
        } catch (e: IOException) {
            connectionStatus = "Error: ${e.message}"
            disconnect()
        }
    }

    private fun disconnect() {
        serialPortManager.stop()
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
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), RECEIVER_NOT_EXPORTED)

        if (usbSerialPort == null || usbSerialPort?.isOpen == false) {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val prober = UsbSerialProber.getDefaultProber()
            for (device in usbManager.deviceList.values) {
                val driver =
                    prober.probeDevice(device) ?: if (device.vendorId == 0x10C4) Cp21xxSerialDriver(device) else null
                if (driver != null) {
                    this.portNum = 0 // Connect to the first port
                    connect(device)
                    break
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(usbPermissionReceiver)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            disconnect()
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
    }
}

@PreviewScreenSizes
@Composable
fun QxDroidApp(
    hexData: List<String> = listOf("DEADBEEF"),
    readOutObjectData: List<ReadOutObject> = listOf(
        ReadOutObject.Command(
        SiCardDetected(
            CardKind.CARD_5,
            2u,
            12345uL
        )
    )),
    connectionStatus: String = "Preview",
    onClearLog: () -> Unit = {},
    onConnectShv: (url: String) -> Unit = {}
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.SHV_CLOUD) }

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
            if (currentDestination == AppDestinations.SI_READER) {
                SIReaderPane(
                    modifier = Modifier.padding(innerPadding),
                    hexData = hexData,
                    readOutObjectData = readOutObjectData,
                    connectionStatus = connectionStatus,
                    onClearLog = onClearLog
                )
            }
            if (currentDestination == AppDestinations.SHV_CLOUD) {
                CloudPane(modifier = Modifier.padding(innerPadding), onConnectShv = onConnectShv)
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    SI_READER("Reader", Icons.Default.AppShortcut),
    SHV_CLOUD("Cloud", Icons.Filled.Cloud),
    PROFILE("Profile", Icons.Default.AccountBox),
}

@Preview(showBackground = true)
@Composable
fun QxDroidAppPreview() {
    QxDroidTheme {
        QxDroidApp()
    }
}
