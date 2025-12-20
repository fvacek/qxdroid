package org.qxqx.qxdroid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.shv.ShvClient

class ShvViewModel : ViewModel() {
    private val shvClient = ShvClient()
    val connectionStatus: StateFlow<ConnectionStatus> = shvClient.connectionStatus

    fun connect(params: ShvConnectionParams) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shvClient.connect("tcp://${params.host}:${params.port}?user=${params.user}&password=${params.password}")
            } catch (e: Exception) {
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
