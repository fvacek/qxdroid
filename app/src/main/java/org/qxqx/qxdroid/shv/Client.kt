package org.qxqx.qxdroid.shv

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

class Client {

    private var socket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Expose incoming messages as a Flow
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> get() = _messages

    @Volatile
    private var connected = false

    suspend fun connect(url: String) = withContext(Dispatchers.IO) {
        if (connected) return@withContext

        val parts = url.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 80

        try {
            // open TCP connection
            socket = Socket(host, port)
            writer = OutputStreamWriter(socket!!.getOutputStream())
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

            connected = true

            // Send initial hello
            sendMessage("hello")

            // Start message reader coroutine
            scope.launch {
                readMessages()
            }

        } catch (e: Exception) {
            connected = false
            disconnect()
            throw e   // Let caller handle properly
        }
    }

    private suspend fun readMessages() = withContext(Dispatchers.IO) {
        try {
            while (connected && socket?.isConnected == true) {
                val message = reader?.readLine() ?: break
                _messages.emit(message)   // send to observers
            }
        } finally {
            disconnect()
        }
    }

    suspend fun sendMessage(message: String) = withContext(Dispatchers.IO) {
        if (!connected) return@withContext
        try {
            writer?.apply {
                write("$message\n")
                flush()
            }
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        connected = false
        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } catch (_: Exception) {
        }
        reader = null
        writer = null
        socket = null

        // cancel background reader
        scope.coroutineContext.cancelChildren()
    }

    fun isConnected(): Boolean =
        connected && socket?.isConnected == true
}
