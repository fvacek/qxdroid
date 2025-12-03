package org.qxqx.qxdroid.shv

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

private const val TAG = "ShvClient"

class ShvClient(private val scope: CoroutineScope) {

    private var socket: Socket? = null
    private var writer: DataOutputStream? = null
    private var reader: DataInputStream? = null

    // Use SharedFlow to emit incoming messages to collectors
    private val _messageFlow = MutableSharedFlow<RpcMessage>()
    val messageFlow: SharedFlow<RpcMessage> = _messageFlow.asSharedFlow()

    suspend fun connect(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val parts = url.split(":")
                val host = parts[0]
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 80

                socket = Socket(host, port)
                writer = DataOutputStream(socket?.getOutputStream())
                reader = DataInputStream(socket?.getInputStream())

                // Start a coroutine to listen for incoming messages
                scope.launch {
                    listenForMessages()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Could not connect to server: $e")
                close()
            }
        }
    }

    private fun sendData(data: ByteArray) {
        writer?.let {
            try {
                it.write(data)
                it.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send data: $e")
                close()
            }
        }
    }

    private suspend fun listenForMessages() {
        withContext(Dispatchers.IO) {
            try {
                while (isActive && reader != null) {
                    val frame_data = getFrameBytes(reader!!)
                    val msg = RpcMessage.fromData(frame_data)
                    _messageFlow.tryEmit(msg)

                    //val buffer = ByteArray(1024) // buffer size
                    //val bytesRead = reader?.read(buffer)
                    //if (bytesRead == -1 || bytesRead == null) {
                    //    // Server closed the connection
                    //    _messageFlow.tryEmit("Server disconnected.")
                    //    break
                    //} else {
                    //    val data = buffer.copyOf(bytesRead)
                    //    println("Read ${data.size} bytes: ${data.joinToString()}")
                    //}
                }
            } catch (e: IOException) {
                Log.e(TAG, "Lost connection to server.")
            } finally {
                close()
            }
        }
    }

    fun close() {
        writer?.close()
        reader?.close()
        socket?.close()
    }
}
