package org.qxqx.qxdroid.shv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import kotlin.math.min

// ==========================================
// Context Stubs (Simulating crate dependencies)
// ==========================================

const val SHV_EPOCH_MSEC: Long = 1517529600000

// Helper to simulate Rust's ByteWriter to count written bytes
class ByteWriter(private val out: OutputStream) {
    var count: Int = 0
        private set

    fun writeByte(b: Byte) {
        out.write(b.toInt())
        count++
    }

    fun writeBytes(b: ByteArray) {
        out.write(b)
        count += b.size
    }
}

// Helper to simulate Rust's ByteReader to count read bytes and peek
class ByteReader(private val input: InputStream) {
    var pos: Int = 0
        private set
    private var peekedByte: Int? = null

    fun peekUByte(): UByte {
        if (peekedByte == null) {
            peekedByte = input.read()
        }
        if (peekedByte == -1) throw makeError(ReadErrorReason.UnexpectedEndOfStream, "Unexpected EOF")
        return peekedByte!!.toUByte()
    }

    fun getUByte(): UByte {
        val b = if (peekedByte != null) {
            val ret = peekedByte!!
            peekedByte = null
            ret
        } else {
            input.read()
        }
        if (b == -1) throw makeError(ReadErrorReason.UnexpectedEndOfStream, "Unexpected EOF")
        pos++
        return b.toUByte()
    }

    fun getBytes(length: Int): ByteArray {
        val bytes = input.readNBytes(length)
        if (bytes.size < length) throw makeError(ReadErrorReason.UnexpectedEndOfStream, "Unexpected EOF")
        return bytes
    }
}

private fun parseUIntHeader(head: UByte): Triple<Int, Int, ULong> {
    val bytesToReadCnt: Int
    val bitLen: Int
    val num: ULong

    val head = head.toUInt()
    if ((head and 128u) == 0u) {
        bytesToReadCnt = 0
        bitLen = 7
        num = (head and 127u).toULong()
    } else if ((head and 64u) == 0u) {
        bytesToReadCnt = 1
        bitLen = 6 + 8
        num = (head and 63u).toULong()
    } else if ((head and 32u) == 0u) {
        bytesToReadCnt = 2
        bitLen = 5 + 2 * 8
        num = (head and 31u).toULong()
    } else if ((head and 16u) == 0u) {
        bytesToReadCnt = 3
        bitLen = 4 + 3 * 8
        num = (head and 15u).toULong()
    } else if (head == 0xFFu) {
        throw makeError(ReadErrorReason.InvalidCharacter, "TERM byte in unsigned int packed data")
    } else {
        bytesToReadCnt = ((head and 0x0Fu) + 4u).toInt()
        bitLen = bytesToReadCnt * 8
        num = 0u
    }

    return Triple(bytesToReadCnt, bitLen, num)
}

private fun readUIntData(reader: ByteReader): Pair<ULong, Int> {
    val head = reader.getUByte()
    val (bytesToReadCnt, bitLen, num1) = parseUIntHeader(head)

    var num = num1
    for (i in 0 until bytesToReadCnt) {
        val r = reader.getUByte()
        num = (num shl 8) + r
    }
    return Pair(num, bitLen)
}

private fun makeError(reason: ReadErrorReason, msg: String): IOException {
    return ReadException(reason, "ChainPack read error - $msg")
}

// Minimal RpcValue implementation to support the translation
sealed class RpcValue {
    class Null : RpcValue()
    data class Bool(val value: Boolean) : RpcValue()
    data class Int(val value: Long) : RpcValue()
    data class UInt(val value: ULong) : RpcValue()
    data class Double(val value: kotlin.Double) : RpcValue()
    data class String(val value: kotlin.String) : RpcValue()
    data class Blob(val value: ByteArray) : RpcValue()
    data class List(val value: kotlin.collections.List<RpcValue>) : RpcValue()
    data class Map(val value: kotlin.collections.Map<kotlin.String, RpcValue>) : RpcValue()
    data class IMap(val value: kotlin.collections.Map<kotlin.Int, RpcValue>) : RpcValue()
    data class Decimal(val mantissa: Long, val exponent: kotlin.Int) : RpcValue()
    data class DateTime(val epochMsec: Long, val utcOffset: kotlin.Int) : RpcValue()

    // MetaData holder (simplified)
    var meta: MetaMap? = null
}

// Minimal MetaMap
class MetaMap {
    val map = LinkedHashMap<MetaKey, RpcValue>()

    sealed class MetaKey {
        data class Str(val s: kotlin.String) : MetaKey()
        data class Int(val i: kotlin.Int) : MetaKey()
    }

    fun insert(key: kotlin.String, value: RpcValue) = map.put(MetaKey.Str(key), value)
    fun insert(key: kotlin.Int, value: RpcValue) = map.put(MetaKey.Int(key), value)
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

class ChainPackWriter(output: OutputStream) {
    private val byteWriter = ByteWriter(output)

    private fun writeByte(b: Byte): kotlin.Int {
        byteWriter.writeByte(b)
        return 1
    }

    private fun writeUByte(b: UByte): kotlin.Int {
        return writeByte(b.toByte())
    }

    private fun writeBytes(b: ByteArray): kotlin.Int {
        byteWriter.writeBytes(b)
        return b.size
    }

    /**
     * Calculates the number of significant bits required to store the number.
     * Roughly equivalent to 64 - leadingZeros.
     */
    private fun significantBitsPartLength(num: ULong): Int {
        // Kotlin/Java has a built-in for this which is likely more efficient,
        // but porting the manual logic from Rust for exact parity:
        var len = 0
        var n = num

        // Check upper 32 bits (0xFFFFFFFF00000000)
        if ((n and 0xFFFFFFFF00000000UL) != 0UL) {
            len += 32
            n = n shr 32
        }
        if ((n and 0xFFFF0000UL) != 0UL) {
            len += 16
            n = n shr 16
        }
        if ((n and 0xFF00UL) != 0UL) {
            len += 8
            n = n shr 8
        }
        if ((n and 0xF0UL) != 0UL) {
            len += 4
            n = n shr 4
        }
        val sigTable4Bit = intArrayOf(0, 1, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4)
        len += sigTable4Bit[n.toInt()]
        return len
    }

    private fun bytesNeeded(bitLen: Int): Int {
        return if (bitLen == 0) {
            1
        } else if (bitLen <= 28) {
            (bitLen - 1) / 7 + 1
        } else {
            (bitLen - 1) / 8 + 2
        }
    }

    private fun expandBitLen(bitLen: Int): Int {
        val byteCnt = bytesNeeded(bitLen)
        return if (bitLen <= 28) {
            byteCnt * (8 - 1) - 1
        } else {
            (byteCnt - 1) * 8 - 1
        }
    }

    private fun writeUintDataHelper(number: ULong, bitLen: Int): kotlin.Int {
        val byteCntMax = 32
        val byteCnt = bytesNeeded(bitLen)

        if (byteCnt > byteCntMax) throw IOException("Max int byte size $byteCntMax exceeded")

        val bytes = ByteArray(byteCntMax)
        var num = number

        for (i in (byteCnt - 1) downTo 0) {
            bytes[i] = (num and 0xFFUL).toByte()
            num = num shr 8
        }

        if (bitLen <= 28) {
            var mask = 0xF0 shl (4 - byteCnt)
            bytes[0] = (bytes[0].toInt() and mask.inv()).toByte()
            mask = mask shl 1
            bytes[0] = (bytes[0].toInt() or mask).toByte()
        } else {
            bytes[0] = (0xF0 or (byteCnt - 5)).toByte()
        }

        val cnt = byteWriter.count
        for (i in 0 until byteCnt) {
            writeByte(bytes[i])
        }
        return byteWriter.count - cnt
    }

    fun writeUintData(number: ULong): kotlin.Int {
        val bitLen = significantBitsPartLength(number)
        val cnt = writeUintDataHelper(number, bitLen)
        return byteWriter.count - (byteWriter.count - cnt) // simplified: just return cnt
    }

    private fun writeIntData(number: Long): kotlin.Int {
        val neg: Boolean
        var num: ULong
        if (number < 0) {
            num = (-number).toULong()
            neg = true
        } else {
            num = number.toULong()
            neg = false
        }

        val bitLen = significantBitsPartLength(num) + 1 // add sign bit
        if (neg) {
            val signPos = expandBitLen(bitLen)
            val signBitMask = 1UL shl signPos
            num = num or signBitMask
        }
        return writeUintDataHelper(num, bitLen)
    }

    private fun writeInt(n: Long): kotlin.Int {
        val startCnt = byteWriter.count
        if (n in 0..63) {
            writeByte(((n % 64) + 64).toByte())
        } else {
            writeUByte(PackingSchema.Int)
            writeIntData(n)
        }
        return byteWriter.count - startCnt
    }

    private fun writeUInt(n: ULong): kotlin.Int {
        val startCnt = byteWriter.count
        if (n < 64u) {
            writeUByte((n % 64u).toUByte())
        } else {
            writeUByte(PackingSchema.UInt)
            writeUintData(n)
        }
        return byteWriter.count - startCnt
    }

    private fun writeDouble(n: Double): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.Double)
        val bits = java.lang.Double.doubleToLongBits(n)
        // Little Endian
        val bytes = ByteArray(8)
        for (i in 0..7) {
            bytes[i] = ((bits ushr (i * 8)) and 0xFF).toByte()
        }
        writeBytes(bytes)
        return byteWriter.count - (startCnt - 1) // Correction for startCnt tracking
    }

    private fun writeDecimal(decimal: RpcValue.Decimal): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.Decimal)
        writeIntData(decimal.mantissa)
        writeIntData(decimal.exponent.toLong())
        return byteWriter.count - (startCnt - 1)
    }

    private fun writeDateTime(dt: RpcValue.DateTime): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.DateTime)
        var msecs = dt.epochMsec - SHV_EPOCH_MSEC
        val offset = (dt.utcOffset / 60 / 15) and 0x7F
        val ms = msecs % 1000

        if (ms == 0L) {
            msecs /= 1000
        }
        if (offset != 0) {
            msecs = msecs shl 7
            msecs = msecs or offset.toLong()
        }
        msecs = msecs shl 2
        if (offset != 0) {
            msecs = msecs or 1
        }
        if (ms == 0L) {
            msecs = msecs or 2
        }
        writeIntData(msecs)
        return byteWriter.count - (startCnt - 1)
    }

    private fun writeList(lst: kotlin.collections.List<RpcValue>): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.List)
        for (v in lst) {
            write(v)
        }
        writeUByte(PackingSchema.TERM)
        return byteWriter.count - (startCnt - 1)
    }

    private fun writeMap(map: kotlin.collections.Map<String, RpcValue>): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.Map)
        for ((k, v) in map) {
            writeString(k)
            write(v)
        }
        writeUByte(PackingSchema.TERM)
        return byteWriter.count - (startCnt - 1)
    }

    private fun writeIMap(map: kotlin.collections.Map<Int, RpcValue>): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.IMap)
        for ((k, v) in map) {
            writeInt(k.toLong())
            write(v)
        }
        writeUByte(PackingSchema.TERM)
        return byteWriter.count - (startCnt - 1)
    }

    private fun writeString(s: String): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.String)
        val data = s.toByteArray(StandardCharsets.UTF_8)
        writeUintData(data.size.toULong())
        writeBytes(data)
        return byteWriter.count - (startCnt - 1)
    }

    private fun writeBlob(data: ByteArray): kotlin.Int {
        val startCnt = writeUByte(PackingSchema.Blob)
        writeUintData(data.size.toULong())
        writeBytes(data)
        return byteWriter.count - (startCnt - 1)
    }

    private fun writeMeta(map: MetaMap): kotlin.Int {
        val startCnt = byteWriter.count
        writeUByte(PackingSchema.MetaMap)
        for ((k, v) in map.map) {
            when (k) {
                is MetaMap.MetaKey.Str -> writeString(k.s)
                is MetaMap.MetaKey.Int -> writeInt(k.i.toLong())
            }
            write(v)
        }
        writeUByte(PackingSchema.TERM)
        return byteWriter.count - startCnt
    }

    fun write(valWrapper: RpcValue): kotlin.Int {
        val startCnt = byteWriter.count
        val mm = valWrapper.meta
        if (mm != null) {
            writeMeta(mm)
        }
        writeValue(valWrapper)
        return byteWriter.count - startCnt
    }

    private fun writeValue(value: RpcValue): kotlin.Int {
        val startCnt = byteWriter.count
        when (value) {
            is RpcValue.Null -> writeUByte(PackingSchema.Null)
            is RpcValue.Bool -> writeUByte(if (value.value) PackingSchema.TRUE else PackingSchema.FALSE)
            is RpcValue.Int -> writeInt(value.value)
            is RpcValue.UInt -> writeUInt(value.value)
            is RpcValue.String -> writeString(value.value)
            is RpcValue.Blob -> writeBlob(value.value)
            is RpcValue.Double -> writeDouble(value.value)
            is RpcValue.Decimal -> writeDecimal(value)
            is RpcValue.DateTime -> writeDateTime(value)
            is RpcValue.List -> writeList(value.value)
            is RpcValue.Map -> writeMap(value.value)
            is RpcValue.IMap -> writeIMap(value.value)
        }
        return byteWriter.count - startCnt
    }
}

enum class ReadErrorReason {
    UnexpectedEndOfStream,
    InvalidCharacter,
}
class ReadException(
    val reason: ReadErrorReason,
    message: String
) : IOException(message)

class ChainPackReader(input: InputStream) {
    private val byteReader = ByteReader(input)

    fun position(): Int = byteReader.pos

    private fun peekUByte(): UByte = byteReader.peekUByte()

    private fun getUByte(): UByte = byteReader.getUByte()

    // Returns Pair(number, bitlen)
    private fun readUIntDataHelper(): Pair<ULong, Int> {
        return readUIntData(byteReader)
    }

    fun readUIntData(): ULong {
        return readUIntDataHelper().first
    }

    private fun readIntData(): Long {
        val (num, bitLen) = readUIntDataHelper()
        val signBitMask = 1UL shl (bitLen - 1)
        val neg = (num and signBitMask) != 0UL
        var sNum = (num and (signBitMask.inv())).toLong()
        if (neg) {
            sNum = -sNum
        }
        return sNum
    }

    private fun readCStringData(): RpcValue {
        val buff = ByteArrayOutputStream()
        while (true) {
            val b = getUByte()
            if (b.toInt() == 0) break
            buff.write(b.toInt())
        }
        val s = String(buff.toByteArray(), StandardCharsets.UTF_8)
        return RpcValue.String(s)
    }

    private fun readStringData(): RpcValue {
        val len = readUIntData()
        // Safety: check len against unreasonable sizes in real code
        val buff = ByteArray(len.toInt())
        for (i in 0 until len.toInt()) {
            buff[i] = getUByte().toByte()
        }
        val s = String(buff, StandardCharsets.UTF_8)
        return RpcValue.String(s)
    }

    private fun readBlobData(): RpcValue {
        val len = readUIntData()
        val buff = ByteArray(len.toInt())
        for (i in 0 until len.toInt()) {
            buff[i] = getUByte().toByte()
        }
        return RpcValue.Blob(buff)
    }

    private fun readListData(): RpcValue {
        val lst = ArrayList<RpcValue>()
        while (true) {
            val b = peekUByte()
            if (b == PackingSchema.TERM) {
                getUByte() // consume TERM
                break
            }
            val `val` = read()
            lst.add(`val`)
        }
        return RpcValue.List(lst)
    }

    private fun readMapData(): RpcValue {
        val map = LinkedHashMap<String, RpcValue>()
        while (true) {
            val b = peekUByte()
            if (b == PackingSchema.TERM) {
                getUByte() // consume TERM
                break
            }
            val k = read()
            val key = if (k is RpcValue.String) {
                k.value
            } else {
                throw makeError(ReadErrorReason.InvalidCharacter, "Invalid Map key '$k'")
            }
            val `val` = read()
            map[key] = `val`
        }
        return RpcValue.Map(map)
    }

    private fun readIMapData(): RpcValue {
        // Rust uses BTreeMap (sorted), assuming TreeMap here for IMap
        val map = TreeMap<Int, RpcValue>()
        while (true) {
            val b = peekUByte()
            if (b == PackingSchema.TERM) {
                getUByte() // consume TERM
                break
            }
            val k = read()
            val key = if (k is RpcValue.Int) {
                k.value.toInt()
            } else {
                throw makeError(ReadErrorReason.InvalidCharacter, "Invalid IMap key '$k'")
            }
            val `val` = read()
            map[key] = `val`
        }
        return RpcValue.IMap(map)
    }

    private fun readDateTimeData(): RpcValue {
        var d = readIntData()
        var offset = 0
        val hasTzOffset = (d and 1L) != 0L
        val hasNotMsec = (d and 2L) != 0L

        d = d shr 2

        if (hasTzOffset) {
            offset = (d and 0x7F).toInt()
            // Sign extension for 7-bit signed int
            offset = (offset shl 25) shr 25 // Int is 32 bit, shift up then arithmetic down
            d = d shr 7
        }

        if (hasNotMsec) {
            d *= 1000
        }
        d += SHV_EPOCH_MSEC

        // offset in Rust code was (offset * 15) * 60 (seconds), here assuming same usage
        return RpcValue.DateTime(d, (offset * 15) * 60)
    }

    private fun readDoubleData(): RpcValue {
        var bits = 0L
        for (i in 0..7) {
            val b = getUByte().toLong() and 0xFF
            bits = bits or (b shl (i * 8))
        }
        return RpcValue.Double(java.lang.Double.longBitsToDouble(bits))
    }

    private fun readDecimalData(): RpcValue {
        val mantissa = readIntData()
        val exponent = readIntData()
        return RpcValue.Decimal(mantissa, exponent.toInt())
    }

    fun tryReadMeta(): MetaMap? {
        val b = peekUByte()
        if (b != PackingSchema.MetaMap) {
            return null
        }
        getUByte() // consume MetaMap tag
        val map = MetaMap()
        while (true) {
            val pb = peekUByte()
            if (pb == PackingSchema.TERM) {
                getUByte()
                break
            }
            val key = readValue()
            val `val` = read()
            when (key) {
                is RpcValue.Int -> map.insert(key.value.toInt(), `val`)
                is RpcValue.String -> map.insert(key.value, `val`)
                else -> throw makeError(ReadErrorReason.InvalidCharacter, "MetaMap key must be int or string, got: $key")
            }
        }
        return map
    }

    fun read(): RpcValue {
        val mm = tryReadMeta()
        val value = readValue()
        if (mm != null) {
            value.meta = mm
        }
        return value
    }

    private fun readValue(): RpcValue {
        val ub = getUByte()

        return if (ub < 128u) {
            if ((ub and 64u).toUInt() == 0u) {
                // tiny UInt
                RpcValue.UInt(ub.toULong() and 63u)
            } else {
                // tiny Int
                RpcValue.Int(ub.toLong() and 63)
            }
        } else {
            when (ub) {
                PackingSchema.Int -> RpcValue.Int(readIntData())
                PackingSchema.UInt -> RpcValue.UInt(readUIntData())
                PackingSchema.Double -> readDoubleData()
                PackingSchema.Decimal -> readDecimalData()
                PackingSchema.DateTime -> readDateTimeData()
                PackingSchema.String -> readStringData()
                PackingSchema.CString -> readCStringData()
                PackingSchema.Blob -> readBlobData()
                PackingSchema.List -> readListData()
                PackingSchema.Map -> readMapData()
                PackingSchema.IMap -> readIMapData()
                PackingSchema.TRUE -> RpcValue.Bool(true)
                PackingSchema.FALSE -> RpcValue.Bool(false)
                PackingSchema.Null -> RpcValue.Null()
                else -> throw makeError(ReadErrorReason.InvalidCharacter, "Invalid Packing schema: $ub")
            }
        }
    }
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

    val reader = ByteReader(input);
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
