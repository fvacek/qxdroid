package org.qxqx.qxdroid.si

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.QxService

class SiViewModel : ViewModel() {
    val readOutLog = mutableStateListOf<SiReadOut>()
    private val _readOutEvents = MutableSharedFlow<SiReadOut>()
    val readOutEvents = _readOutEvents.asSharedFlow()
    val hexLog = mutableStateListOf<String>()
    
    var connectionStatus by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected("Not connected"))
        private set

    private var qxService: QxService? = null

    fun setService(service: QxService) {
        qxService = service
        viewModelScope.launch {
            service.siConnectionStatus.collectLatest {
                connectionStatus = it
            }
        }
        viewModelScope.launch {
            service.readOutEvents.collect {
                readOutLog.add(it)
                _readOutEvents.emit(it)
            }
        }
        viewModelScope.launch {
            service.hexLog.collect {
                hexLog.add(it)
            }
        }
    }

    fun clearLogs() {
        readOutLog.clear()
        hexLog.clear()
    }
}
