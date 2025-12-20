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
import androidx.activity.viewModels
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.qxqx.qxdroid.ui.theme.QxDroidTheme
import androidx.lifecycle.lifecycleScope
import org.qxqx.qxdroid.shv.ShvViewModel
import org.qxqx.qxdroid.si.SiViewModel

private const val usbSerialPortNum: Int = 0

class MainActivity : ComponentActivity() {

    private val shvViewModel: ShvViewModel by viewModels()
    private val siViewModel: SiViewModel by viewModels()
    private lateinit var usbPermissionReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("MainActivity", "onCreate()")
        super.onCreate(savedInstanceState)

        // Start bridging the Si events to the SHV client
        shvViewModel.observeAndPublishSiData(
            scope = lifecycleScope,
            source = siViewModel.readOutEvents
        )

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        val usbDevice: UsbDevice? =
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if (usbDevice != null) {
                                connect(usbDevice)
                            }
                        } else {
                            siViewModel.setStatus(ConnectionStatus.Disconnected("Permission denied"))
                        }
                    }
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            QxDroidTheme {
                QxDroidApp(siViewModel, shvViewModel)
            }
        }
        handleIntent(intent)
    }

    private fun connect(device: UsbDevice) {
        siViewModel.disconnect()

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        siViewModel.setStatus(ConnectionStatus.Connecting("USB OTG"))
        var driver: UsbSerialDriver? = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            if (device.vendorId == 0x10C4) {
                siViewModel.setStatus(ConnectionStatus.Connecting("VID 10c4 detected, trying manual driver..."))
                driver = Cp21xxSerialDriver(device)
            } else {
                siViewModel.setStatus(ConnectionStatus.Disconnected("No driver found"))
                return
            }
        }

        if (driver.ports.isEmpty()) {
            siViewModel.setStatus(ConnectionStatus.Disconnected("No ports"))
            return
        }
        val usbSerialPort = driver.ports[usbSerialPortNum]
        val usbConnection: UsbDeviceConnection? = usbManager.openDevice(driver.device)
        if (usbConnection == null && !usbManager.hasPermission(driver.device)) {
            siViewModel.setStatus(ConnectionStatus.Connecting("Permission pending"))
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
            siViewModel.setStatus(ConnectionStatus.Disconnected("Cannot open device"))
            return
        }

        siViewModel.connect(usbSerialPort, usbConnection)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? =
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            device?.let {
                connect(it)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        Log.i("MainActivity", "onNewIntent()")
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        Log.i("MainActivity", "onResume()")
        super.onResume()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // Use RECEIVER_NOT_EXPORTED for safety on newer Android versions
        registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)

        // Only try auto-connect if we aren't already connected/connecting
        if (siViewModel.connectionStatus is ConnectionStatus.Disconnected) {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val prober = UsbSerialProber.getDefaultProber()
            for (device in usbManager.deviceList.values) {
                val driver = prober.probeDevice(device) ?: if (device.vendorId == 0x10C4) Cp21xxSerialDriver(device) else null
                if (driver != null) {
                    connect(device)
                    break
                }
            }
        }
    }

    override fun onPause() {
        Log.i("MainActivity", "onPause()")
        super.onPause()
        unregisterReceiver(usbPermissionReceiver)
    }

    override fun onStop() {
        Log.i("MainActivity", "onStop()")
        super.onStop()
        if (!isChangingConfigurations) {
            siViewModel.disconnect()
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
    }
}

@PreviewScreenSizes
@Composable
fun QxDroidApp(
    siViewModel: SiViewModel = viewModel(),
    shvViewModel: ShvViewModel = viewModel()
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.SI_READER) }

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
            when (currentDestination) {
                AppDestinations.SI_READER -> {
                    SIReaderPane(
                        viewModel = siViewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
                AppDestinations.SHV_CLOUD -> {
                    ShvPane(
                        viewModel = shvViewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
                AppDestinations.PROFILE -> {
                    // Profile Pane could go here
                }
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
