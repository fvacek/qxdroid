package org.qxqx.qxdroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.shv.ShvClient

class ShvViewModel(application: Application) : AndroidViewModel(application) {
    private val shvClient = ShvClient()
    private val appSettings = AppSettings(application)

    val connectionStatus: StateFlow<ConnectionStatus> = shvClient.connectionStatus

    val connectionParams: StateFlow<ShvConnectionParams> = appSettings.shvConnectionParams
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
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
}
