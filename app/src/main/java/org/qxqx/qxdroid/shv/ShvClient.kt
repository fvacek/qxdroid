package org.qxqx.qxdroid.shv

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.qxqx.qxdroid.bytesToHex
import org.qxqx.qxdroid.sha1
import org.qxqx.qxdroid.shv.getFrameBytes
import org.qxqx.qxdroid.shv.Protocol
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest

private const val TAG = "ShvClient"

class RpcException(message: String) : Exception(message)

class ShvClient {

    private var socket: Socket? = null
    private var writer: DataOutputStream? = null
    private var reader: DataInputStream? = null

    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _messageFlow = MutableSharedFlow<RpcMessage>(replay = 10)
    val messageFlow: SharedFlow<RpcMessage> = _messageFlow.asSharedFlow()

    suspend fun connect(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(url)
                if (uri.scheme != "tcp") {
                    throw IllegalArgumentException("Invalid scheme: ${uri.scheme}")
                }
                val host = uri.host ?: throw IllegalArgumentException("No host specified")
                val port = uri.port?: 3755
                val user = uri.getQueryParameter("user") ?: throw IllegalArgumentException("No user specified")
                val password = uri.getQueryParameter("password") ?: throw IllegalArgumentException("No password specified")

                Log.i(TAG, "Connecting to shv broker: $host:$port")
                socket = Socket(host, port)
                Log.i(TAG, "Connected OK")
                writer = DataOutputStream(socket?.getOutputStream())
                reader = DataInputStream(socket?.getInputStream())

                clientScope.launch {
                    listenForMessages()
                }

                val rqid1 = sendHello()
                val res = receiveResponse(rqid1).resultE()
                val nonce = res.toMap()?.get("nonce")?.asString()
                    ?: throw RpcException("Invalid response, invalid nonce")

                val rqid2 = sendLogin(user, password, nonce)
                receiveResponse(rqid2).resultE()
                Log.i(TAG, "Login to shv broker was successful")

            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                close()
                throw e
            }
        }
    }

    fun sendHello(): Long {
        val msg = RpcRequest("", "hello")
        val rqid = msg.requestId() ?: 0L
        sendMessage(msg)
        return rqid
    }

    fun sendLogin(user: String, password: String, nonce: String): Long {
        val sha1pwd = sha1(nonce + sha1(password))

        val param = RpcValue.Map(
            mapOf(
                "login" to RpcValue.Map(
                    mapOf(
                        "password" to RpcValue.String(sha1pwd),
                        "type" to RpcValue.String("SHA1"),
                        "user" to RpcValue.String(user),
                    )
                ),
                "options" to RpcValue.Map(
                    mapOf(
                        "idleWatchDogTimeOut" to RpcValue.Int(180),
                    )
                ),
            )
        )

        val msg = RpcRequest("", "login", param)
        val rqid = msg.requestId() ?: 0L
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
        Log.d(TAG, "Waiting for response for request: $request_id")
        val msg = withTimeout(5000) {
            messageFlow
                .onEach { m: RpcMessage -> Log.d(TAG, "Message in flow: $m") }
                .first { m: RpcMessage -> m.requestId() == request_id }
        }

        Log.d(TAG, "Found message for rqid $request_id: $msg")

        if (msg !is RpcResponse) {
            throw RpcException("Unexpected message type received for request $request_id: ${msg.javaClass.simpleName}")
        }

        val err = msg.error()
        if (err != null) {
            throw RpcException("RPC error for request $request_id: ${err.message}")
        }
        return msg
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
        try {
            while (clientScope.isActive && reader != null) {
                try {
                    val frameData = getFrameBytes(reader!!)
                    Log.d(TAG, "Frame received: ${bytesToHex(frameData)}")
                    val msg = RpcMessage.fromData(frameData)
                    val emitted = _messageFlow.tryEmit(msg)
                    Log.d(TAG, "Emitting message: $msg. Success: $emitted")
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame, skipping.", e)
                }
            }
        } finally {
            Log.i(TAG, "Message listener stopped.")
        }
    }

    fun close() {
        Log.i(TAG, "Closing connection.")
        clientScope.cancel()
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket resources", e)
        }
    }
}
