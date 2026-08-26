package org.qxqx.qxdroid.shv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.qxqx.qxdroid.ConnectionStatus
import org.qxqx.qxdroid.sha1
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import androidx.core.net.toUri
import timber.log.Timber

private const val RPC_MSG = "RpcMsg"

class RpcException(message: String) : Exception(message)

class ShvClient {
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected(""))
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private var socket: Socket? = null
    private var writer: DataOutputStream? = null
    private var reader: DataInputStream? = null
    private var pingJob: Job? = null
    private val sendLock = Any()

    private var clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionId = 0L
    private val connectLock = Mutex()

    // replay parameter controls how many past values new collectors receive when they start listening.
    private val _messageFlow = MutableSharedFlow<RpcMessage>()
    val messageFlow: SharedFlow<RpcMessage> = _messageFlow.asSharedFlow()

    // This map will hold deferred objects for pending requests.
    // Use ConcurrentHashMap for thread safety.
    private val pendingResponses = ConcurrentHashMap<Long, CompletableDeferred<RpcResponse>>()

    suspend fun connect(url: String) = connectLock.withLock {
        // Stop the previous listener before replacing any connection state.
        close()
        val newConnectionId = synchronized(sendLock) {
            connectionId += 1
            connectionId
        }
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        clientScope = newScope
        withContext(Dispatchers.IO) {
            try {
                val uri = url.toUri()
                if (uri.scheme != "tcp") {
                    throw IllegalArgumentException("Invalid scheme: ${uri.scheme}")
                }
                val host = uri.host ?: throw IllegalArgumentException("No host specified")
                val port = if (uri.port == 0) 3755 else uri.port
                val user = uri.getQueryParameter("user") ?: throw IllegalArgumentException("No user specified")
                val password = uri.getQueryParameter("password") ?: throw IllegalArgumentException("No password specified")

                _connectionStatus.value = ConnectionStatus.Connecting("$host:$port")
                Timber.i("Connecting to shv broker: $host:$port")
                socket = Socket(host, port)
                Timber.i("Connected OK")
                writer = DataOutputStream(socket?.getOutputStream())
                reader = DataInputStream(socket?.getInputStream())

                val connectionReader = reader
                    ?: throw IllegalStateException("Failed to create socket reader")
                newScope.launch {
                    listenForMessages(connectionReader, newScope, newConnectionId)
                }

                val helloResponse = sendHello()
                val nonce = helloResponse.toMap()?.get("nonce")?.asString()
                    ?: throw RpcException("Invalid response, invalid nonce")

                val pingIntervalSeconds = 90
                sendLogin(user, password, nonce, pingIntervalSeconds)

                Timber.i("Login to shv broker was successful")
                _connectionStatus.value = ConnectionStatus.Connected
                pingJob = newScope.launch {
                    while (isActive) {
                        try {
                            callShvMethod(".app", "ping")
                        } catch (e: Exception) {
                            Timber.w("Ping failed: ${e.message}")
                        }
                        delay(pingIntervalSeconds * 1000L)
                    }
                }

            } catch (e: Exception) {
                Timber.e(e, "Connection error")
                val errorMessage = e.message ?: "Unknown error"
                _connectionStatus.value = ConnectionStatus.Disconnected(errorMessage)
                close()
                throw e
            }
        }
    }

    suspend fun sendHello(): RpcValue {
        Timber.i("Sending hello")
        return callShvMethod("", "hello")
    }

    suspend fun sendLogin(user: String, password: String, nonce: String, pingIntervalSeconds: Int): RpcValue {
        Timber.i("Sending login")
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
                        "idleWatchDogTimeOut" to RpcValue.Int(pingIntervalSeconds * 2),
                    )
                ),
            )
        )

        return callShvMethod("", "login", param)
    }

    private fun sendData(data: ByteArray) {
        synchronized(sendLock) {
            writer?.let {
                try {
                    it.write(data)
                    it.flush()
                } catch (e: IOException) {
                    Timber.e("Failed to send data: $e")
                    close()
                }
            }
        }
    }

    private suspend fun callShvMethod(path: String, method: String, params: RpcValue? = null, userId: String? = null): RpcValue {
        val request = RpcRequest(path, method, params, userId)
        val requestId = request.requestId() ?: throw IllegalStateException("Request has no ID")
        val deferred = CompletableDeferred<RpcResponse>()

        try {
            pendingResponses[requestId] = deferred
            sendMessage(request)
            Timber.d("Waiting for response for request: $requestId")

            val response = withTimeout(5000) {
                deferred.await()
            }

            return response.resultE()
        } finally {
            // Clean up the map in case of timeout or cancellation
            Timber.i("removing request id: $requestId")

            pendingResponses.remove(requestId)
        }
    }

    fun sendMessage(msg: RpcMessage) {
        Timber.tag(RPC_MSG).d("S<== $msg")
        val data = msg.value.toChainPack()
        val ba = ByteArrayOutputStream()
        val writer = ChainPackWriter(ba)
        writer.writeUintData((data.size + 1).toULong())
        val header = ba.toByteArray() + byteArrayOf(Protocol.CHAIN_PACK.toByte())
        sendData(header + data)
    }

    private fun listenForMessages(
        connectionReader: DataInputStream,
        connectionScope: CoroutineScope,
        listenerConnectionId: Long,
    ) {
        try {
            while (connectionScope.isActive) {
                try {
                    val frameData = getFrameBytes(connectionReader)
                    val msg = RpcMessage.fromData(frameData)
                    Timber.tag(RPC_MSG).d("R==> $msg")
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
                        Timber.e(e, "Socked closed")
                        if (isCurrentConnection(listenerConnectionId)) {
                            _connectionStatus.value = ConnectionStatus.Disconnected("Socked closed")
                        }
                        break
                    }
                    Timber.e(e, "Error processing frame, skipping.")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing frame, skipping.")
                }
            }
        } finally {
            Timber.i("Message listener stopped.")
            // A listener from an earlier connection must not clean up a newer one.
            if (isCurrentConnection(listenerConnectionId)) {
                pendingResponses.values.forEach { it.cancel() }
                pendingResponses.clear()
            }
        }
    }

    private fun isCurrentConnection(listenerConnectionId: Long): Boolean =
        synchronized(sendLock) { listenerConnectionId == connectionId }

    fun close() {
        synchronized(sendLock) {
            Timber.i("Closing connection.")
            if (_connectionStatus.value !is ConnectionStatus.Disconnected) {
                _connectionStatus.value = ConnectionStatus.Disconnected("Connection closed")
            }
            pingJob?.cancel()
            clientScope.cancel()
            connectionId += 1
            pendingResponses.values.forEach { it.cancel() }
            pendingResponses.clear()
            try {
                writer?.close()
                reader?.close()
                socket?.close()
            } catch (e: IOException) {
                Timber.w(e, "Error closing socket resources")
            } finally {
                writer = null
                reader = null
                socket = null
            }
        }
    }
}
