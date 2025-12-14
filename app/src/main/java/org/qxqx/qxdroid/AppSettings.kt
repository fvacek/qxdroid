package org.qxqx.qxdroid

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(context: Context) {

    private val dataStore = context.dataStore

    private object PreferencesKeys {
        val HOST = stringPreferencesKey("host")
        val PORT = stringPreferencesKey("port")
        val USER = stringPreferencesKey("user")
        val PASSWORD = stringPreferencesKey("password")
    }

    val connectionParams: Flow<ConnectionParams> = dataStore.data.map {
        ConnectionParams(
            host = it[PreferencesKeys.HOST] ?: "10.0.2.2",
            port = it[PreferencesKeys.PORT] ?: "3755",
            user = it[PreferencesKeys.USER] ?: "test",
            password = it[PreferencesKeys.PASSWORD] ?: "test"
        )
    }

    suspend fun saveConnectionParams(connectionParams: ConnectionParams) {
        dataStore.edit {
            it[PreferencesKeys.HOST] = connectionParams.host
            it[PreferencesKeys.PORT] = connectionParams.port
            it[PreferencesKeys.USER] = connectionParams.user
            it[PreferencesKeys.PASSWORD] = connectionParams.password
        }
    }
}
