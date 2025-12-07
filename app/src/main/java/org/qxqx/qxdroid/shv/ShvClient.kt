package org.qxqx.qxdroid.shv

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.bytesToHex
import org.qxqx.qxdroid.sha1
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.toMap
import kotlin.text.get

private const val TAG = "ShvClient"
private const val RPC_MSG = "RpcMsg"

class RpcException(message: String) : Exception(message)

class ShvClient {
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected(""))
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private var socket: Socket? = null
    private var writer: DataOutputStream? = null
    private var reader: DataInputStream? = null

    private var clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // replay parameter controls how many past values new collectors receive when they start listening.
    private val _messageFlow = MutableSharedFlow<RpcMessage>()
    val messageFlow: SharedFlow<RpcMessage> = _messageFlow.asSharedFlow()

    // This map will hold deferred objects for pending requests.
    // Use ConcurrentHashMap for thread safety.
    private val pendingResponses = ConcurrentHashMap<Long, CompletableDeferred<RpcResponse>>()

    suspend fun connect(url: String) {
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // Re-create the scope
        withContext(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(url)
                if (uri.scheme != "tcp") {
                    throw IllegalArgumentException("Invalid scheme: ${uri.scheme}")
                }
                val host = uri.host ?: throw IllegalArgumentException("No host specified")
                val port = if (uri.port == 0) 3755 else uri.port
                val user = uri.getQueryParameter("user") ?: throw IllegalArgumentException("No user specified")
                val password = uri.getQueryParameter("password") ?: throw IllegalArgumentException("No password specified")

                _connectionStatus.value = ConnectionStatus.Connecting("$host:$port")
                Log.i(TAG, "Connecting to shv broker: $host:$port")
                socket = Socket(host, port)
                Log.i(TAG, "Connected OK")
                writer = DataOutputStream(socket?.getOutputStream())
                reader = DataInputStream(socket?.getInputStream())

                clientScope.launch {
                    listenForMessages()
                }

                val helloResponse = sendHello()
                val nonce = helloResponse.toMap()?.get("nonce")?.asString()
                    ?: throw RpcException("Invalid response, invalid nonce")

                sendLogin(user, password, nonce)

                Log.i(TAG, "Login to shv broker was successful")
                _connectionStatus.value = ConnectionStatus.Connected

            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                val errorMessage = e.message ?: "Unknown error"
                _connectionStatus.value = ConnectionStatus.Disconnected(errorMessage)
                close()
                throw e
            }
        }
    }

    suspend fun sendHello(): RpcValue {
        Log.i(TAG, "Sending hello")
        return sendRequest("", "hello")
    }

    suspend fun sendLogin(user: String, password: String, nonce: String): RpcValue {
        Log.i(TAG, "Sending login")
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

        return sendRequest("", "login", param)
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

    private suspend fun sendRequest(path: String, method: String, params: RpcValue? = null, userId: String? = null): RpcValue {
        val request = RpcRequest(path, method, params)
        val requestId = request.requestId() ?: throw IllegalStateException("Request has no ID")
        val deferred = CompletableDeferred<RpcResponse>()

        try {
            pendingResponses[requestId] = deferred
            sendMessage(request)
            Log.d(TAG, "Waiting for response for request: $requestId")

            val response = withTimeout(5000) {
                deferred.await()
            }

            return response.resultE()
        } finally {
            // Clean up the map in case of timeout or cancellation
            Log.i(TAG, "removing request id: $requestId")

            pendingResponses.remove(requestId)
        }
    }

    fun sendMessage(msg: RpcMessage) {
        Log.d(RPC_MSG, "S<== $msg")
        val data = msg.value.toChainPack()
        val ba = ByteArrayOutputStream()
        val writer = ChainPackWriter(ba)
        writer.writeUintData((data.size + 1).toULong())
        sendData(ba.toByteArray() + byteArrayOf(Protocol.CHAIN_PACK.toByte()))
        sendData(data)
    }

    private fun listenForMessages() {
        try {
            while (clientScope.isActive && reader != null) {
                try {
                    val frameData = getFrameBytes(reader!!)
                    val msg = RpcMessage.fromData(frameData)
                    Log.d(RPC_MSG, "R==> $msg")
                    if (msg is RpcResponse) {
                        // It's a response, find the pending request and complete it.
                        val requestId = msg.requestId()
                        if (pendingResponses.containsKey(requestId)) {
                            pendingResponses[requestId]?.complete(msg)
                            continue
                        }
                    }
                    _messageFlow.tryEmit(msg) // You can still use the flow for notifications

                } catch (e: ReadException) {
                    if (e.reason == ReadErrorReason.UnexpectedEndOfStream) {
                        Log.e(TAG, "Socked closed", e)
                        _connectionStatus.value = ConnectionStatus.Disconnected("Socked closed")
                        break
                    }
                    Log.e(TAG, "Error processing frame, skipping.", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing frame, skipping.", e)
                }
            }
        } finally {
            Log.i(TAG, "Message listener stopped.")
            // When the listener stops, fail all pending requests.
            pendingResponses.values.forEach { it.cancel() }
            pendingResponses.clear()
        }
    }

    fun close() {
        Log.i(TAG, "Closing connection.")
        if (_connectionStatus.value !is ConnectionStatus.Disconnected) {
            _connectionStatus.value = ConnectionStatus.Disconnected("Connection closed")
        }
        clientScope.cancel()
        try {
            writer?.close()
            reader?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket resources", e)
        } finally {
            writer = null
            reader = null
            socket = null
        }
    }
}
