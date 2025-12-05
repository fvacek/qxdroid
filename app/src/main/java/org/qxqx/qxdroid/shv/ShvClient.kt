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
import org.qxqx.qxdroid.bytesToHex
import java.io.ByteArrayOutputStream
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

                Log.i(TAG, "Connecting to shv broker: $host:$port")
                socket = Socket(host, port)
                writer = DataOutputStream(socket?.getOutputStream())
                reader = DataInputStream(socket?.getInputStream())

                sendHello()

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

    fun sendHello() {
        val msg = RpcRequest("", "hello")
        sendMessage(msg)
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

    fun sendMessage(msg: RpcMessage) {
        val data = msg.value.toChainPack()
        val ba = ByteArrayOutputStream()
        val writer = ChainPackWriter(ba)
        writer.writeUintData((data.size + 1).toULong())
        sendData(ba.toByteArray() + byteArrayOf(Protocol.CHAIN_PACK.toByte()))
        sendData(data)
    }

    private suspend fun listenForMessages() {
        withContext(Dispatchers.IO) {
            try {
                while (isActive && reader != null) {
                    val frameData = getFrameBytes(reader!!)
                    Log.d(TAG, "Frame received: ${bytesToHex(frameData)}")
                    val msg = RpcMessage.fromData(frameData)
                    _messageFlow.tryEmit(msg)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Lost connection to server: $e")
                close()
            } catch (e: ReceiveFrameError) {
                Log.e(TAG, "Error while receiving frame: $e.")
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
