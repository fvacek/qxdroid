package org.qxqx.qxdroid

data class SiDataFrame(
    val command: Int,
    val data: ByteArray,
) {
    override fun toString(): String {
        return "${bytesToHex(byteArrayOf(command.toByte()))} | ${bytesToHex(data)}"
    }

    fun toByteArray(): ByteArray {
        val commandByte = command.toByte()
        val lengthByte = data.size.toByte()

        // The CRC is computed including the command byte and the length byte.
        val crcData = byteArrayOf(commandByte, lengthByte)
        val crcValue = CrcCalculator.crc(crcData)
        val crc1 = (crcValue shr 8).toByte()
        val crc0 = crcValue.toByte()

        return byteArrayOf(STX, commandByte, lengthByte) + data + byteArrayOf(crc1, crc0, ETX)
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

        fun fromData(frame: ByteArray) : SiDataFrame {
            val command = frame[1].toInt() and 0xFF
            val length = frame[2].toInt() and 0xFF
            val data = frame.copyOfRange(3, 3 + length)
            val receivedCrcBytes = frame.copyOfRange(3 + length, 5 + length)

            // The CRC is computed including the command byte and the length byte.
            val dataForCrc = frame.copyOfRange(1, 3)
            val calculatedCrc = CrcCalculator.crc(dataForCrc)
            val receivedCrc = ((receivedCrcBytes[0].toInt() and 0xFF) shl 8) or (receivedCrcBytes[1].toInt() and 0xFF)

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
