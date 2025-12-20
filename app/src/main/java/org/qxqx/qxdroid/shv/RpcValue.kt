package org.qxqx.qxdroid.shv

// import android.util.Log
import org.qxqx.qxdroid.bytesToHex
import org.qxqx.qxdroid.si.CardKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.collections.contentEquals
import kotlin.collections.contentHashCode
import kotlin.collections.joinToString
import kotlin.math.pow

private const val TAG = "RpcValue"

const val SHV_EPOCH_MSEC: Long = 1517529600000

// Minimal RpcValue implementation to support the translation
sealed class RpcValue {

    var meta: MetaMap? = null

    fun toCpon(): kotlin.String {
        return (meta?.valueToCpon()?: "") + valueToCpon()
    }

    abstract fun valueToCpon(): kotlin.String
    class Null : RpcValue() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return true
        }
        override fun hashCode(): kotlin.Int {
            return javaClass.hashCode()
        }
        override fun valueToCpon(): kotlin.String {
            return "null"
        }
    }
    data class Bool(val value: Boolean) : RpcValue() {
        override fun valueToCpon(): kotlin.String {
            return if (value) "true" else "false"
        }
    }
    data class Int(val value: Long) : RpcValue() {
        constructor(value: kotlin.Int) : this(value.toLong())

        override fun valueToCpon(): kotlin.String {
            return value.toString()
        }
    }
    data class UInt(val value: ULong) : RpcValue() {
        constructor(value: kotlin.UInt) : this(value.toULong())

        override fun valueToCpon(): kotlin.String {
            return value.toString()
        }
    }

    data class Double(val value: kotlin.Double) : RpcValue() {
        override fun valueToCpon(): kotlin.String {
            return value.toString()
        }
    }

    data class String(val value: kotlin.String) : RpcValue() {
        override fun valueToCpon(): kotlin.String {
            return "\"$value\""
        }
    }

    data class Blob(val value: ByteArray) : RpcValue() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Blob
            return value.contentEquals(other.value)
        }

        override fun hashCode(): kotlin.Int {
            return value.contentHashCode()
        }

        override fun valueToCpon(): kotlin.String {
            return "x\"${bytesToHex(value)}\""
        }
    }
    data class List(val value: kotlin.collections.List<RpcValue>) : RpcValue() {
        override fun valueToCpon(): kotlin.String {
            return "[" + value.joinToString(",") { it.valueToCpon() } + "]"
        }
    }

    data class Map(val value: kotlin.collections.Map<kotlin.String, RpcValue>) : RpcValue() {
        override fun valueToCpon(): kotlin.String {
            return "{" + value.entries.joinToString(",") { "\"${it.key}\":${it.value.valueToCpon()}" } + "}"
        }
    }

    data class IMap(val value: kotlin.collections.Map<kotlin.Int, RpcValue>) : RpcValue() {
        override fun valueToCpon(): kotlin.String {
            return "i{" + value.entries.joinToString(",") { "${it.key}:${it.value.valueToCpon()}" } + "}"
        }
    }

    data class Decimal(val mantissa: Long, val exponent: kotlin.Int) : RpcValue() {
        override fun valueToCpon(): kotlin.String {
            val d = mantissa * 10.0.pow(exponent)
            return d.toString()
        }
    }

    data class DateTime(val epochMsec: Long, val utcOffset: kotlin.Int) : RpcValue() {
        fun millisSinceEpoch(): Long {
            return epochMsec
        }
        fun toJavaDateTime(): OffsetDateTime {
            val offset = ZoneOffset.ofHoursMinutes(utcOffset / 60, utcOffset % 60)
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMsec), offset)
        }
        override fun valueToCpon(): kotlin.String {
            return "d\"${toJavaDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}\""
        }
    }

    fun toChainPack(): ByteArray {
        val ba = ByteArrayOutputStream()
        val writer = ChainPackWriter(ba)
        writer.write(this)
        return ba.toByteArray()
    }

    fun toInt(): Long? {
        return when (this) {
            is Int -> value
            is UInt -> value.toLong()
            else -> null
        }
    }
    fun asString(): kotlin.String? {
        return when (this) {
            is String -> value
            else -> null
        }
    }
    fun toList(): kotlin.collections.List<RpcValue>? {
        return when (this) {
            is List -> value
            else -> null
        }
    }
    fun toMap(): kotlin.collections.Map<kotlin.String, RpcValue>? {
        return when (this) {
            is Map -> value
            else -> null
        }
    }
    fun toIMap(): kotlin.collections.Map<kotlin.Int, RpcValue>? {
        return when (this) {
            is IMap -> value
            else -> null
        }
    }

    companion object {
        fun fromChainPack(data: ByteArray): RpcValue {
            val reader = ChainPackReader(ByteArrayInputStream(data))
            return reader.read()
        }
    }
}

// Minimal MetaMap
class MetaMap {
    val map = LinkedHashMap<MetaKey, RpcValue>()

    sealed class MetaKey {
        data class Str(val s: kotlin.String) : MetaKey()
        data class Int(val i: kotlin.Int) : MetaKey()
    }

    fun get(key: kotlin.String): RpcValue? = map.get(MetaKey.Str(key))
    fun get(key: kotlin.Int): RpcValue? = map.get(MetaKey.Int(key))
    fun insert(key: kotlin.String, value: RpcValue) = map.put(MetaKey.Str(key), value)
    fun insert(key: kotlin.Int, value: RpcValue) = map.put(MetaKey.Int(key), value)
    fun remove(key: kotlin.String) = map.remove(MetaKey.Str(key))
    fun remove(key: kotlin.Int) = map.remove(MetaKey.Int(key))

    fun valueToCpon(): kotlin.String {
        return "<" + map.entries.joinToString(",") {
            val keystr = when (it.key) {
                is MetaKey.Str -> "\"${(it.key as MetaKey.Str).s}\""
                is MetaKey.Int -> "${(it.key as MetaKey.Int).i}"
            }
            "$keystr:${it.value.valueToCpon()}"
        } + ">"
    }
}

// ==========================================
// ChainPack Implementation
// ==========================================

object PackingSchema {
    const val Null: UByte = 128u
    const val UInt: UByte = 129u
    const val Int: UByte = 130u
    const val Double: UByte = 131u
    const val BoolDeprecated: UByte = 132u
    const val Blob: UByte = 133u
    const val String: UByte = 134u
    const val DateTimeEpochDeprecated: UByte = 135u
    const val List: UByte = 136u
    const val Map: UByte = 137u
    const val IMap: UByte = 138u
    const val MetaMap: UByte = 139u
    const val Decimal: UByte = 140u
    const val DateTime: UByte = 141u
    const val CString: UByte = 142u
    const val FALSE: UByte = 253u
    const val TRUE: UByte = 254u
    const val TERM: UByte = 255u
}





sealed class ReceiveFrameError(message: String) : Exception(message) {
    class FramingError(message: String) : ReceiveFrameError(message)
    class FrameTooLarge(message: String) : ReceiveFrameError(message)
    class TimeoutError(message: String) : ReceiveFrameError(message)
}

object Protocol {
    const val CHAIN_PACK = 1
}

fun getFrameBytes(input: InputStream): ByteArray {
    val reader = ByteReader(input)
    val (frameLen, _) = readUIntData(reader)
    val limit = 50 * 1024 * 1024
    if (frameLen.toInt() > limit) {
        throw ReceiveFrameError.FrameTooLarge("Frame length exceeds limit of $limit bytes.")
    }
    val protocol = reader.getUByte().toInt()
    if (protocol != Protocol.CHAIN_PACK) {
        throw ReceiveFrameError.FramingError("Invalid protocol type received: $protocol")
    }
    val frameData = reader.getBytes(frameLen.toInt() - 1)
    return frameData
}
