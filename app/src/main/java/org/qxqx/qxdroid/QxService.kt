package org.qxqx.qxdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDeviceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

import kotlinx.coroutines.launch
import org.qxqx.qxdroid.shv.RpcSignal
import org.qxqx.qxdroid.shv.ShvClient
import org.qxqx.qxdroid.si.UsbSerialPortManager
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.UsbSiProtocolDecoder
import org.qxqx.qxdroid.si.SiReadOut
import org.qxqx.qxdroid.si.toSiRecCommand
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class QxService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // SI related
    private val _siConnectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected"))
    val siConnectionStatus = _siConnectionStatus.asStateFlow()

    private val _readOutEvents = MutableSharedFlow<SiReadOut>()
    val readOutEvents = _readOutEvents.asSharedFlow()

    private val _usbHexLog = MutableSharedFlow<String>()
    val usbHexLog = _usbHexLog.asSharedFlow()

    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private lateinit var usbSerialPortManager: UsbSerialPortManager

    private lateinit var usbSiProtocolDecoder: UsbSiProtocolDecoder

    // SHV related
    private val shvClient = ShvClient()
    val shvConnectionStatus = shvClient.connectionStatus

    private lateinit var appSettings: AppSettings
    @Volatile
    private var httpPostParams = HttpPostParams("", false)

    inner class LocalBinder : Binder() {
        fun getService(): QxService = this@QxService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        
        appSettings = AppSettings(this)
        serviceScope.launch {
            appSettings.httpPostParams.collectLatest { params ->
                httpPostParams = params
            }
        }

        usbSiProtocolDecoder = UsbSiProtocolDecoder(
            sendSiFrame = { frame -> usbSerialPortManager.sendDataFrame(frame) },
            onCardRead = { card ->
                val readOut = SiReadOut.Card(card)
                serviceScope.launch {
                    publishReadOut(readOut)
                }
            }
        )

        usbSerialPortManager = UsbSerialPortManager(
            onRawData = { data ->
                serviceScope.launch { _usbHexLog.emit(bytesToHex(data)) }
            },
            onDataFrame = { frame ->
                usbSiProtocolDecoder.onDataFrame(frame)
                handleSiDataFrame(frame)
            },
            onError = { e ->
                _siConnectionStatus.value = ConnectionStatus.Disconnected("Error: ${e.message}")
            }
        )

        createNotificationChannel()
        
        // Start foreground with only dataSync initially to avoid SecurityException when no USB is connected.
        // We will add connectedDevice type later when a USB device is actually connected.
        startForegroundWithTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, "Service started")

    }

    private fun startForegroundWithTypes(type: Int, content: String) {
        val notification = createNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun connectSi(port: UsbSerialPort, connection: UsbDeviceConnection) {
        if (usbSerialPort?.device?.deviceName == port.device.deviceName && _siConnectionStatus.value is ConnectionStatus.Connected) {
            Timber.d("SI already connected to this device")
            return
        }
        
        try {
            disconnectSi()
            usbConnection = connection
            usbSerialPort = port
            port.open(usbConnection)
            port.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            usbSerialPortManager.start(port)
            _siConnectionStatus.value = ConnectionStatus.Connected
            
            // Now that we have a connected device, we can include the connectedDevice FGS type.
            startForegroundWithTypes(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                "SI Connected"
            )
            
            Timber.i("SI Connected to ${port.device.deviceName}")
        } catch (e: IOException) {
            disconnectSi("Error: ${e.message}")
        }
    }

    fun disconnectSi(error: String? = null) {
        try {
            usbSerialPortManager.stop()
            usbSerialPort?.close()
            usbConnection?.close()
        } catch (e: Exception) {
            Timber.w(e, "Serial port close failed")
        } finally {
            usbSerialPort = null
            usbConnection = null
            _siConnectionStatus.value = ConnectionStatus.Disconnected(error ?: "Disconnected")
            
            // Downgrade FGS type to just dataSync since USB is disconnected.
            startForegroundWithTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, "SI Disconnected")
        }
    }

    fun connectShv(params: ShvConnectionParams) {
        serviceScope.launch {
            try {
                shvClient.connect("tcp://${params.host}:${params.port}?user=${params.user}&password=${params.password}")
                updateNotification("SHV Connected")
            } catch (e: Exception) {
                Timber.e(e, "SHV connection failed")
            }
        }
    }

    fun disconnectShv() {
        shvClient.close()
        updateNotification("SHV Disconnected")
    }

    fun setHttpPostParams(params: HttpPostParams) {
        httpPostParams = params
    }

    private fun postCard(readOut: SiReadOut) {
        if (readOut !is SiReadOut.Card || !httpPostParams.enabled || httpPostParams.url.isBlank()) {
            return
        }

        val url = httpPostParams.url.trim()
        serviceScope.launch {
            var connection: HttpURLConnection? = null
            try {
                val uri = URI(url)
                require(uri.scheme == "http" || uri.scheme == "https") {
                    "HTTP post URL must use http or https"
                }
                connection = uri.toURL().openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output ->
                    output.write(readOut.card.toRpcValue().toCpon().toByteArray(Charsets.UTF_8))
                }
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Timber.w("Posting card to $url failed with HTTP $responseCode")
                } else {
                    Timber.d("Posted card ${readOut.card.cardNumber} to $url")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to post card to $url")
            } finally {
                connection?.disconnect()
            }
        }
    }

    fun publishReadOut(readOut: SiReadOut) {
        serviceScope.launch {
            _readOutEvents.emit(readOut)
            publishToShv(readOut)
            postCard(readOut)
        }
    }

    private fun handleSiDataFrame(dataFrame: SiDataFrame) {
        val cmd = toSiRecCommand(dataFrame)
        val readOut = when (cmd) {
            is SiCardDetected -> SiReadOut.CardDetected(cmd)
            is SiCardRemoved -> SiReadOut.CardRemoved(cmd)
            else -> null
        }
        readOut?.let {
            publishReadOut(it)
        }
    }

    private fun publishToShv(readOut: SiReadOut) {
        if (shvClient.connectionStatus.value is ConnectionStatus.Connected) {
            try {
                val method = when (readOut) {
                    is SiReadOut.Card -> "read"
                    is SiReadOut.CardDetected -> "detected"
                    is SiReadOut.CardRemoved -> "removed"
                    is SiReadOut.Punch -> "punch"
                }
                val sig = RpcSignal("siReader", method, "chng", readOut.toRpcValue())
                shvClient.sendMessage(sig)
                Timber.d("Published to SHV: $readOut")
            } catch (e: Exception) {
                Timber.e(e, "Failed to publish SiReadOut to SHV")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "QxDroid Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QxDroid Service")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(content))
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSi()
        disconnectShv()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "QxServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}
