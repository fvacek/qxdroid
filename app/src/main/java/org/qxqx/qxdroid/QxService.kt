package org.qxqx.qxdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.os.Binder
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
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.shv.RpcSignal
import org.qxqx.qxdroid.shv.ShvClient
import org.qxqx.qxdroid.si.SerialPortManager
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.SiProtocolDecoder
import org.qxqx.qxdroid.si.SiReadOut
import org.qxqx.qxdroid.si.toSiRecCommand
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import timber.log.Timber
import java.io.IOException

class QxService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // SI related
    private val _siConnectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected"))
    val siConnectionStatus = _siConnectionStatus.asStateFlow()

    private val _readOutEvents = MutableSharedFlow<SiReadOut>()
    val readOutEvents = _readOutEvents.asSharedFlow()

    private val _hexLog = MutableSharedFlow<String>()
    val hexLog = _hexLog.asSharedFlow()

    private var usbConnection: UsbDeviceConnection? = null
    private var usbSerialPort: UsbSerialPort? = null
    private lateinit var serialPortManager: SerialPortManager

    private lateinit var siProtocolDecoder: SiProtocolDecoder

    // SHV related
    private val shvClient = ShvClient()
    val shvConnectionStatus = shvClient.connectionStatus

    inner class LocalBinder : Binder() {
        fun getService(): QxService = this@QxService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        
        siProtocolDecoder = SiProtocolDecoder(
            sendSiFrame = { frame -> serialPortManager.sendDataFrame(frame) },
            onCardRead = { card ->
                val readOut = SiReadOut.Card(card)
                serviceScope.launch {
                    _readOutEvents.emit(readOut)
                    publishToShv(readOut)
                }
            }
        )

        serialPortManager = SerialPortManager(
            onRawData = { data ->
                serviceScope.launch { _hexLog.emit(bytesToHex(data)) }
            },
            onDataFrame = { frame ->
                siProtocolDecoder.onDataFrame(frame)
                handleSiDataFrame(frame)
            },
            onError = { e ->
                _siConnectionStatus.value = ConnectionStatus.Disconnected("Error: ${e.message}")
            }
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Service started"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun connectSi(port: UsbSerialPort, connection: UsbDeviceConnection) {
        try {
            usbConnection = connection
            usbSerialPort = port
            port.open(usbConnection)
            port.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
            serialPortManager.start(port)
            _siConnectionStatus.value = ConnectionStatus.Connected
            updateNotification("SI Connected")
        } catch (e: IOException) {
            disconnectSi("Error: ${e.message}")
        }
    }

    fun disconnectSi(error: String? = null) {
        try {
            serialPortManager.stop()
            usbSerialPort?.close()
            usbConnection?.close()
        } catch (e: IOException) {
            Timber.w(e, "Serial port close failed")
        } finally {
            usbSerialPort = null
            usbConnection = null
            _siConnectionStatus.value = ConnectionStatus.Disconnected(error ?: "Disconnected")
            updateNotification("SI Disconnected")
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

    private fun handleSiDataFrame(dataFrame: SiDataFrame) {
        val cmd = toSiRecCommand(dataFrame)
        val readOut = when (cmd) {
            is SiCardDetected -> SiReadOut.CardDetected(cmd)
            is SiCardRemoved -> SiReadOut.CardRemoved(cmd)
            else -> null
        }
        readOut?.let {
            serviceScope.launch {
                _readOutEvents.emit(it)
                publishToShv(it)
            }
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
