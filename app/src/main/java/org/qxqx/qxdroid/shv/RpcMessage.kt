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
            return RpcMessage(v)
        }

        private fun newValue(): RpcValue {
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

    fun setRequestId(id: ULong): RpcMessage {
        val mm = checkMeta()
        mm.insert(Tag.RequestId.value, RpcValue.UInt(id))
        value.meta = mm
        return this
    }

    fun setShvPath(shvPath: String): RpcMessage {
        val mm = checkMeta()
        mm.insert(Tag.ShvPath.value, RpcValue.String(shvPath))
        value.meta = mm
        return this
    }

    fun setMethod(method: String): RpcMessage {
        val mm = checkMeta()
        mm.insert(Tag.Method.value, RpcValue.String(method))
        value.meta = mm
        return this
    }

    fun setParam(param: RpcValue?): RpcMessage = apply {
        val map = (value as? RpcValue.IMap)?.value ?: throw IllegalStateException("RpcMessage value is not an IMap")
        if (map is MutableMap) {
            map[Key.Params.value] = param ?: RpcValue.Null()
        }
    }

    fun setUserId(userId: String?): RpcMessage {
        val mm = checkMeta()
        if (userId == null) {
            mm.remove(Tag.UserId.value)
        } else {
            mm.insert(Tag.UserId.value, RpcValue.String(userId))
        }
        value.meta = mm
        return this
    }
}

private fun createMeta(): MetaMap {
    val mm = MetaMap()
    mm.insert(1, RpcValue.Int(1))
    return mm
}

class RpcRequest(
    val path: String,
    val method: String,
    val param: RpcValue? = null,
    val userId: String? = null,
) : RpcMessage(run {
    requestId += 1UL
    val msg = RpcMessage()
    msg.setRequestId(requestId)
    msg.setShvPath(path)
    msg.setMethod(method)
    msg.setParam(param)
    msg.value
}) {
    companion object {
        private var requestId: ULong = 0UL
    }
}
