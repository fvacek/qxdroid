package org.qxqx.qxdroid.shv

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

private const val TAG = "ChainPackWriter"

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