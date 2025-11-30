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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.ui.theme.QxDroidTheme
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var deviceId: Int = 0
    private var portNum: Int = 0
    private lateinit var usbPermissionReceiver: BroadcastReceiver
    private var usbSerialPort: UsbSerialPort? = null
    private val readLog = mutableStateListOf<ReadActivity>()
    private val hexLog = mutableStateListOf<String>()
    private var connectionStatus by mutableStateOf("Disconnected")

    private lateinit var siReader: SiReader
    private lateinit var serialPortManager: SerialPortManager

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
                QxDroidApp(hexLog, readLog, connectionStatus, onClearLog = { clearLog() }, onBeep = { beep() })
            }
        }
        handleIntent(intent)
    }

    private fun clearLog() {
        readLog.clear()
        hexLog.clear()
    }

    private fun beep() {
        // doesn't work
        val frame = SiDataFrame(0xe0, byteArrayOf())
        serialPortManager.sendDataFrame(frame)
    }

    private fun logDataFrame(dataFrame: SiDataFrame) {
        runOnUiThread {
            val cmd = toSiRecCommand(dataFrame)
            if (cmd is SiCardDetected || cmd is SiCardRemoved) {
                readLog.add(ReadActivity.Command(cmd))
            }
        }
    }

    private fun logSiCard(card: SiCard) {
        //Log.d("MainActivity", "SI card read: ${card}.")
        runOnUiThread {
            readLog.add(ReadActivity.CardRead(card))
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
    readActivityData: List<ReadActivity> = listOf(ReadActivity.Command(SiCardDetected(CardKind.CARD_5, 2u, 12345uL))),
    connectionStatus: String = "Preview",
    onClearLog: () -> Unit = {},
    onBeep: () -> Unit = {}
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
                var isHexPaneExpanded by rememberSaveable { mutableStateOf(false) }
                val hexListState = rememberLazyListState()
                val readActivityDataState = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()

                LaunchedEffect(hexData.size) {
                    if (hexData.isNotEmpty()) {
                        coroutineScope.launch {
                            hexListState.animateScrollToItem(hexData.size - 1)
                        }
                    }
                }
                LaunchedEffect(readActivityData.size) {
                    if (readActivityData.isNotEmpty()) {
                        coroutineScope.launch {
                            readActivityDataState.animateScrollToItem(readActivityData.size - 1)
                        }
                    }
                }

                Column(modifier = Modifier.padding(innerPadding)) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusColor = when {
                            connectionStatus == "Connected" -> Color(0, 102, 0)
                            connectionStatus.startsWith("Error") ||
                                    connectionStatus.startsWith("Disconnected (") ||
                                    connectionStatus == "Permission denied" ||
                                    connectionStatus == "Disconnected"
                            -> Color.Red
                            else -> Color.Gray
                        }
                        Text(
                            text = connectionStatus,
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(statusColor)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = onClearLog) {
                            Text(text = "Clear Log")
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            modifier = if (isHexPaneExpanded) Modifier.weight(1f) else Modifier
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isHexPaneExpanded = !isHexPaneExpanded }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    "Hex Data",
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (isHexPaneExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = if (isHexPaneExpanded) "Collapse" else "Expand"
                                )
                            }
                            HorizontalDivider()
                            if (isHexPaneExpanded) {
                                LazyColumn(state = hexListState) {
                                    items(hexData) { line ->
                                        Text(text = line, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                        ) {
                            Text(
                                "Card Activity",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            HorizontalDivider()
                            ReadActivityLog(log = readActivityData, listState = readActivityDataState)
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
fun ReadActivityLog(
    log: List<ReadActivity>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    LazyColumn(modifier = modifier, state = listState) {
        items(log) { activity ->
            when (activity) {
                is ReadActivity.CardRead -> {
                    val card = activity.card
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "Card ${card.cardNumber}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "(${card.cardKind})"
                            )
                        }
                        Text(
                            text = "Start: ${timeToString(card.startTime)}, Finish: ${timeToString(card.finishTime)}, Check: ${timeToString(card.checkTime)}"
                        )
                    }
                }
                is ReadActivity.Command -> {
                    Text(
                        text = activity.command.toString(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }
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