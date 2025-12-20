package org.qxqx.qxdroid

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Define a DataStore for connection settings
private val Context.shvConnectionDataStore: DataStore<Preferences> by preferencesDataStore(name = "shvConnection")

// Define a separate DataStore for serial port settings
private val Context.serialPortDataStore: DataStore<Preferences> by preferencesDataStore(name = "serialPort")

// Data class for Serial Port parameters (assuming its structure)
data class SerialPortParams(
    val baudRate: Int,
    val dataBits: Int,
    val stopBits: Int,
    val parity: Int
)

class AppSettings(context: Context) {

    // Reference to the connection DataStore
    private val shvConnectionDataStore = context.shvConnectionDataStore
    // Reference to the serial port DataStore
    private val serialPortDataStore = context.serialPortDataStore

    private object ShvConnectionKeys {
        val HOST = stringPreferencesKey("host")
        val PORT = stringPreferencesKey("port")
        val USER = stringPreferencesKey("user")
        val PASSWORD = stringPreferencesKey("password")
        val API_TOKEN = stringPreferencesKey("apiToken")
    }

    private object SerialPortKeys {
        val BAUD_RATE = intPreferencesKey("baud_rate")
        val DATA_BITS = intPreferencesKey("data_bits")
        val STOP_BITS = intPreferencesKey("stop_bits")
        val PARITY = intPreferencesKey("parity")
    }

    // Flow for Connection Parameters
    val shvConnectionParams: Flow<ShvConnectionParams> = shvConnectionDataStore.data.map { prefs ->
        ShvConnectionParams(
            host = prefs[ShvConnectionKeys.HOST] ?: "10.0.2.2",
            port = prefs[ShvConnectionKeys.PORT] ?: "3755",
            user = prefs[ShvConnectionKeys.USER] ?: "test",
            password = prefs[ShvConnectionKeys.PASSWORD] ?: "test",
            apiToken = prefs[ShvConnectionKeys.API_TOKEN] ?: "foo-bar"
        )
    }

    // Flow for Serial Port Parameters
    val serialPortParams: Flow<SerialPortParams> = serialPortDataStore.data.map { prefs ->
        SerialPortParams(
            baudRate = prefs[SerialPortKeys.BAUD_RATE] ?: 9600,
            dataBits = prefs[SerialPortKeys.DATA_BITS] ?: 8,
            stopBits = prefs[SerialPortKeys.STOP_BITS] ?: 1,
            parity = prefs[SerialPortKeys.PARITY] ?: 0
        )
    }

    suspend fun saveConnectionParams(params: ShvConnectionParams) {
        shvConnectionDataStore.edit { prefs ->
            prefs[ShvConnectionKeys.HOST] = params.host
            prefs[ShvConnectionKeys.PORT] = params.port
            prefs[ShvConnectionKeys.USER] = params.user
            prefs[ShvConnectionKeys.PASSWORD] = params.password
            prefs[ShvConnectionKeys.API_TOKEN] = params.apiToken
        }
    }

    suspend fun saveSerialPortParams(params: SerialPortParams) {
        serialPortDataStore.edit { prefs ->
            prefs[SerialPortKeys.BAUD_RATE] = params.baudRate
            prefs[SerialPortKeys.DATA_BITS] = params.dataBits
            prefs[SerialPortKeys.STOP_BITS] = params.stopBits
            prefs[SerialPortKeys.PARITY] = params.parity
        }
    }
}
