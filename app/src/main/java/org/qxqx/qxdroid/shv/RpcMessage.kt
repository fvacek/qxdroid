package org.qxqx.qxdroid.shv

import java.io.ByteArrayInputStream

enum class Tag(val value: Int) {
    RequestId(8),
    ShvPath(9),
    Method(10),
    CallerIds(11),
    ProtocolType(12),
    RevCallerIds(13),
    Access(14),
    UserId(16),
    AccessLevel(17),
    SeqNo(18),
    Source(19),
    Repeat(20),
    Part(21),
    MAX(-1); // or whatever value MAX represents
}

enum class Key(val value: Int) {
    Params(1),
    Result(2),
    Error(3),
    Delay(4),
    Abort(5);
}

open class RpcMessage(
    val value: RpcValue
) {
    init {
        require(value is RpcValue.IMap) { "RpcMessage value must be an RpcValue.IMap" }
    }

    constructor() : this(newValue())

    companion object {
        fun fromData(data: ByteArray): RpcMessage {
            val reader = ChainPackReader(ByteArrayInputStream(data))
            val v = reader.read()
            if (v !is RpcValue.IMap) {
                throw IllegalArgumentException("Invalid RPC message data, not IMap")
            }
            val mm = v.meta?: throw IllegalArgumentException("Invalid RPC message data, no meta")
            if (mm.get(Tag.RequestId.value) == null) {
                throw IllegalArgumentException("Rpc signal not supported yet")
            }
            if (mm.get(Tag.Method.value) == null) {
                return RpcResponse(v)
            }
            return RpcRequest(v)
        }

        internal fun newValue(): RpcValue {
            val v = RpcValue.IMap(mutableMapOf())
            v.meta = createMeta()
            return v
        }
    }

    private fun checkMeta(): MetaMap {
        if (value.meta == null) {
            value.meta = createMeta()
        }
        return value.meta!!
    }

    fun requestId(): Long? = getTag(Tag.RequestId)?.toInt()
    fun setRequestId(id: Long) = setTag(Tag.RequestId, RpcValue.Int(id))

    fun shvPath(): String? = getTag(Tag.ShvPath)?.toString()
    fun setShvPath(shvPath: String) = setTag(Tag.ShvPath, RpcValue.String(shvPath))

    fun method(): String? = getTag(Tag.Method)?.toString()
    fun setMethod(method: String) = setTag(Tag.Method, RpcValue.String(method))

    fun setParam(param: RpcValue?) = apply {
        val map = (value as? RpcValue.IMap)?.value ?: throw IllegalStateException("RpcMessage value is not an IMap")
        if (map is MutableMap) {
            map[Key.Params.value] = param ?: RpcValue.Null()
        }
    }

    fun userId(): String? = getTag(Tag.UserId)?.toString()
    fun setUserId(userId: String?) = setTag(Tag.UserId, userId?.let { RpcValue.String(it) })

    fun callerIds(): RpcValue? = getTag(Tag.CallerIds)
    fun setCallerIds(callerIds: RpcValue?) = setTag(Tag.CallerIds, callerIds)


    fun getTag(tag: Tag): RpcValue? {
        val mm = checkMeta()
        return mm.get(tag.value)
    }
    fun setTag(tag: Tag, value: RpcValue?) {
        val mm = checkMeta()
        if (value == null) {
            mm.remove(tag.value)
        } else {
            mm.insert(tag.value, value)
        }
    }
}

private fun createMeta(): MetaMap {
    val mm = MetaMap()
    mm.insert(1, RpcValue.Int(1))
    return mm
}

class RpcRequest(value: RpcValue) : RpcMessage(value) {

    constructor(
        path: String,
        method: String,param: RpcValue? = null,
        userId: String? = null, // Note: userId was not used in your original constructor logic.
    ) : this(createRpcValue(path, method, param, userId))

    companion object {
        private var requestId: Long = 0L

        private fun createRpcValue(
            path: String,
            method: String,
            param: RpcValue?,
            userId: String?,
        ): RpcValue {
            synchronized(this) {
                requestId += 1L
                val msg = RpcMessage()
                msg.setRequestId(requestId)
                msg.setShvPath(path)
                msg.setMethod(method)
                msg.setParam(param)
                 msg.setUserId(userId)
                return msg.value
            }
        }
    }
}
class RpcResponse(value: RpcValue) : RpcMessage(value) {

    constructor() : this(newValue())

    enum class RpcErrorCode(val value: Int) {
        NoError(0),
        InvalidRequest(1),	// The data sent is not a valid Request object.
        MethodNotFound(2),	// The method does not exist / is not available.
        InvalidParam(3),		// Invalid method parameter(s).
        InternalError(4),		// Internal RPC error.
        ParseError(5),		// Invalid JSON was received by the server. An error occurred on the server while parsing the JSON text.
        MethodCallTimeout(6),
        MethodCallCancelled(7),
        MethodCallException(8),
        PermissionDenied(9),
        LoginRequired(10),
        UserIDRequired(11),
        NotImplemented(12),
        TryAgainLater(13),
        AbortRequestInvalid(14),
    }
    private enum class ErrorKey(val value: Int) { Code(1), Message(2) }

    class RpcError(val code: RpcErrorCode, val message: String)

    companion object {
        fun fromRequest(request: RpcRequest): RpcResponse {
            val msg = RpcResponse()
            msg.setRequestId(
                request.requestId() ?: throw IllegalArgumentException("Request ID is missing")
            )
            msg.setCallerIds(request.callerIds())
            return msg
        }
    }

    fun result(): RpcValue? {
        return value.toIMap()?.get(Key.Result.value)
    }

    fun resultE(): RpcValue {
        val result = result()
        if (result == null) {
            val err = error()?: throw RuntimeException("Result is invalid")
            throw RuntimeException("RPC call error: ${err.code} ${err.message}")
        }
        return result
    }

    fun error(): RpcError? {
        val errmap = value.toIMap()?.get(Key.Error.value)?.toIMap() ?: return null
        val code = errmap.get(ErrorKey.Code.value)?.toInt()?.toInt()
        val message = errmap.get(ErrorKey.Message.value)?.toString()
        return RpcError(RpcErrorCode.entries[code?: 0], message?: "Unknown error")
    }
}



