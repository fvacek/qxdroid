package org.qxqx.qxdroid.shv

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.TreeMap

private const val TAG = "ChainPackReader"

// Helper to simulate Rust's ByteReader to count read bytes and peek
class ByteReader(private val input: InputStream) {
    var pos: Int = 0
        private set
    private var peekedByte: Int? = null

    fun peekUByte(): UByte {
        if (peekedByte == null) {
            val b2 = input.read()
            //Log.d(CAHINPACK_READER, "Byte received: ${bytesToHex(byteArrayOf(b2.toByte()))}")
            peekedByte = b2
        }
        if (peekedByte == -1) throw ReadException(ReadErrorReason.UnexpectedEndOfStream, "ChainPack read error - Unexpected EOF")
        return peekedByte!!.toUByte()
    }

    fun getUByte(): UByte {
        val b = if (peekedByte != null) {
            val b2 = peekedByte!!
            peekedByte = null
            b2
        } else {
            val b2 = input.read()
            //Log.d(CAHINPACK_READER, "Byte received: ${bytesToHex(byteArrayOf(b2.toByte()))}")
            b2
        }
        if (b == -1) throw ReadException(ReadErrorReason.UnexpectedEndOfStream, "ChainPack read error - Unexpected EOF")
        pos++
        return b.toUByte()
    }

    fun getBytes(length: Int): ByteArray {
        val bytes = input.readNBytes(length)
        if (bytes.size < length) throw ReadException(ReadErrorReason.UnexpectedEndOfStream, "ChainPack read error - Unexpected EOF")
        return bytes
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

private fun makeError(reason: ReadErrorReason, msg: String): IOException {
    return ReadException(reason, "ChainPack read error - $msg")
}

private fun parseUIntHeader(head: UByte): Triple<Int, Int, ULong> {
    val bytesToReadCnt: Int
    val bitLen: Int
    val num: ULong

    val headValue = head.toUInt()
    if ((headValue and 128u) == 0u) {
        bytesToReadCnt = 0
        bitLen = 7
        num = (headValue and 127u).toULong()
    } else if ((headValue and 64u) == 0u) {
        bytesToReadCnt = 1
        bitLen = 6 + 8
        num = (headValue and 63u).toULong()
    } else if ((headValue and 32u) == 0u) {
        bytesToReadCnt = 2
        bitLen = 5 + 2 * 8
        num = (headValue and 31u).toULong()
    } else if ((headValue and 16u) == 0u) {
        bytesToReadCnt = 3
        bitLen = 4 + 3 * 8
        num = (headValue and 15u).toULong()
    } else if (headValue == 0xFFu) {
        throw makeError(ReadErrorReason.InvalidCharacter, "TERM byte in unsigned int packed data")
    } else {
        bytesToReadCnt = ((headValue and 0x0Fu) + 4u).toInt()
        bitLen = bytesToReadCnt * 8
        num = 0u
    }

    return Triple(bytesToReadCnt, bitLen, num)
}

// Public utility function for external use
fun readUIntData(reader: ByteReader): Pair<ULong, Int> {
    val head = reader.getUByte()
    val (bytesToReadCnt, bitLen, num1) = parseUIntHeader(head)

    var num = num1
    for (i in 0 until bytesToReadCnt) {
        val r = reader.getUByte()
        num = (num shl 8) + r
    }
    return Pair(num, bitLen)
}

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
        val buff = java.io.ByteArrayOutputStream()
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