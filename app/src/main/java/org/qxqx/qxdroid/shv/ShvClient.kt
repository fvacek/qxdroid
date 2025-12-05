package org.qxqx.qxdroid.shv

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.qxqx.qxdroid.bytesToHex
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest

private const val TAG = "ShvClient"

private fun sha1(input: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val hash = digest.digest(input.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
}

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

                // Start a coroutine to listen for incoming messages
                scope.launch {
                    listenForMessages()
                }

                val rqid1 = sendHello()
                val res = receiveResponse(rqid1).resultE()
                val nonce = res.toMap()?.get("nonce")?.toString() ?: throw IllegalArgumentException("Invalid response, nonce is null")

                val rqid2 = sendLogin(nonce)
                receiveResponse(rqid2).resultE()

            } catch (e: IOException) {
                Log.e(TAG, "Could not connect to server: $e")
                close()
            }
        }
    }

    fun sendHello(): Long {
        val msg = RpcRequest("", "hello")
        val rqid = msg.requestId()?: 0L
        sendMessage(msg)
        return rqid
    }
    fun sendLogin(nonce: String): Long {
        // {"login":{"password":"0471b43505462fcfc4208aee533bd9f785058b13","type":"SHA1","user":"test"},"options":{"idleWatchDogTimeOut":180}}
        val user = "test"
        val password = "test"
        val sha1pwd = sha1(nonce + sha1(password))
        val param = RpcValue.Map(mapOf(
            "login" to RpcValue.Map(mapOf(
                "password" to RpcValue.String(sha1pwd),
                "type" to RpcValue.String("SHA1"),
                "user" to RpcValue.String(user),
            )),
            "options" to RpcValue.Map(mapOf(
                "idleWatchDogTimeOut" to RpcValue.Int(180),
            )),
        ))

        val msg = RpcRequest("", "login", param)
        val rqid = msg.requestId()?: 0L
        sendMessage(msg)
        return rqid
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

    private suspend fun receiveResponse(request_id: Long): RpcResponse {
        val resp = withTimeout(5000) {
            messageFlow
                .filterIsInstance<RpcResponse>()
                .first { it.requestId() == request_id }
        }
        return resp
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
