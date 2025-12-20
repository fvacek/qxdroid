package org.qxqx.qxdroid.shv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.AppSettings
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.ShvConnectionParams
import org.qxqx.qxdroid.si.SiReadOut
import timber.log.Timber

class ShvViewModel(application: Application) : AndroidViewModel(application) {
    private val shvClient = ShvClient()
    private val appSettings = AppSettings(application)

    val connectionStatus: StateFlow<ConnectionStatus> = shvClient.connectionStatus

    val connectionParams: StateFlow<ShvConnectionParams> = appSettings.shvConnectionParams
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Companion.WhileSubscribed(5000),
            initialValue = ShvConnectionParams("10.0.2.2", "3755", "test", "test", "foo-bar")
        )

    fun connect(params: ShvConnectionParams) {
        viewModelScope.launch(Dispatchers.IO) {
            appSettings.saveConnectionParams(params)
            try {
                shvClient.connect("tcp://${params.host}:${params.port}?user=${params.user}&password=${params.password}")
            } catch (_: Exception) {
                // Connection error is handled by ShvClient updating connectionStatus
            }
        }
    }

    fun disconnect() {
        shvClient.close()
    }

    override fun onCleared() {
        super.onCleared()
        shvClient.close()
    }

    fun observeAndPublishSiData(scope: CoroutineScope, source: Flow<SiReadOut>) {
        scope.launch(Dispatchers.IO) {
            source.collect { readOut ->
                if (connectionStatus.value is ConnectionStatus.Connected) {
                    try {
                        // Example: Send a signal to a specific path
                        val method = when (readOut) {
                            is SiReadOut.Card -> "read"
                            is SiReadOut.CardDetected -> "detected"
                            is SiReadOut.CardRemoved -> "removed"
                            is SiReadOut.Punch -> "punch"
                        }
                        val sig = RpcSignal("siReader", method, "chng", readOut.toRpcValue())
                        shvClient.sendMessage(sig)
                        Timber.d("Would publish to SHV: $readOut")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to publish SiReadOut to SHV")
                    }
                }
            }
        }
    }
}