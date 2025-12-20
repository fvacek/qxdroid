package org.qxqx.qxdroid.shv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.AppSettings
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.QxService
import org.qxqx.qxdroid.ShvConnectionParams

class ShvViewModel(application: Application) : AndroidViewModel(application) {
    private val appSettings = AppSettings(application)

    var connectionStatus = org.qxqx.qxdroid.shv.ShvClient().connectionStatus // Temporary default
        private set

    val connectionParams: StateFlow<ShvConnectionParams> = appSettings.shvConnectionParams
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ShvConnectionParams("10.0.2.2", "3755", "test", "test", "foo-bar")
        )

    private var qxService: QxService? = null

    fun setService(service: QxService) {
        qxService = service
        connectionStatus = service.shvConnectionStatus
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
