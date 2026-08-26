package org.qxqx.qxdroid.shv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.AppSettings
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.HttpPostParams
import org.qxqx.qxdroid.QxService
import org.qxqx.qxdroid.ShvConnectionParams

class ShvViewModel(application: Application) : AndroidViewModel(application) {
    private val appSettings = AppSettings(application)

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(
        ConnectionStatus.Disconnected("Service unavailable")
    )
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    val connectionParams: StateFlow<ShvConnectionParams> = appSettings.shvConnectionParams
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ShvConnectionParams("10.0.2.2", "3755", "test", "test", "foo-bar")
        )

    val postQxEnabled: StateFlow<Boolean> = appSettings.postQxEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val httpPostParams: StateFlow<HttpPostParams> = appSettings.httpPostParams
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HttpPostParams("", false)
        )

    private var qxService: QxService? = null
    private var connectionStatusJob: Job? = null

    fun setService(service: QxService) {
        qxService = service
        connectionStatusJob?.cancel()
        _connectionStatus.value = service.shvConnectionStatus.value
        connectionStatusJob = viewModelScope.launch {
            service.shvConnectionStatus.collectLatest { status ->
                _connectionStatus.value = status
            }
        }
    }

    fun savePostQxEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettings.savePostQxEnabled(enabled)
        }
    }

    fun saveHttpPostParams(params: HttpPostParams) {
        viewModelScope.launch {
            appSettings.saveHttpPostParams(params)
            qxService?.setHttpPostParams(params)
        }
    }

    fun connect(params: ShvConnectionParams) {
        viewModelScope.launch {
            appSettings.saveConnectionParams(params)
            qxService?.connectShv(params)
        }
    }

    fun disconnect() {
        qxService?.disconnectShv()
    }
}
