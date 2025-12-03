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

class RpcMessage(
    val value: RpcValue
) {
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

    fun setShvPath(shvPath: String): RpcMessage {
        var mm = value.meta
        if (mm == null) {
            mm = createMeta()
        }
        mm.insert(Tag.ShvPath.value, RpcValue.String(shvPath))
        value.meta = mm
        return this
    }
}

private fun createMeta(): MetaMap {
    val mm = MetaMap()
    mm.insert(1, RpcValue.Int(1))
    return mm
}
