package org.qxqx.qxdroid.si

import org.qxqx.qxdroid.bytesToHex

data class SiDataFrame(
    val command: Int,
    val data: ByteArray,
) {
    override fun toString(): String {
        return "${bytesToHex(byteArrayOf(command.toByte()))}|${bytesToHex(data)}"
    }

    fun toByteArray(): ByteArray {
        val commandByte = command.toByte()
        val lengthByte = data.size.toByte()

        // The CRC is computed including the command byte and the length byte.
        val rawData = byteArrayOf(commandByte, lengthByte) + data
        val crcValue = CrcCalculator.crc(rawData)
        val crc1 = (crcValue shr 8).toByte()
        val crc0 = crcValue.toByte()

        return byteArrayOf(STX) + rawData + byteArrayOf(crc1, crc0, ETX)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SiDataFrame

        if (command != other.command) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + data.contentHashCode()
        return result
    }
    companion object {
        private const val STX: Byte = 0x02
        private const val ETX: Byte = 0x03

        private const val TAG = "SiDataFrame"

        fun fromData(frame: ByteArray) : SiDataFrame {
            //Log.d(TAG, "frame: ${bytesToHex(frame)}")
            val command = getUByte(frame, 1).toInt()
            val length = getUByte(frame, 2).toInt()
            val data = frame.copyOfRange(3, 3 + length)
            //Log.d(TAG, "data: ${bytesToHex(data)}")
            // The CRC is computed including the command byte and the length byte.
            val dataForCrc = frame.copyOfRange(1, 3 + length)
            //Log.d(TAG, "dataForCrc: ${bytesToHex(dataForCrc)}")
            val calculatedCrc = CrcCalculator.crc(dataForCrc)
            //Log.d(TAG, "calculatedCrc: ${calculatedCrc.toString(16)}")
            val receivedCrc = getUInt16(frame, length + 3).toInt()
            //Log.d(TAG, "receivedCrc: ${receivedCrc.toString(16)}")

            val isCrcOk = calculatedCrc == receivedCrc && data.size == length
            if (isCrcOk) {
                return SiDataFrame(
                    command = command,
                    data = data,
                )
            } else {
                throw RuntimeException("CRC check failed, data: ${bytesToHex(frame)}")
            }
        }
    }
}