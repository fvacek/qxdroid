package org.qxqx.qxdroid

object CrcCalculator {

    private const val POLY = 0x8005
    private const val BITF = 0x8000

    /**
     * Computes the custom CRC used in your protocol.
     * This function preserves 100% compatibility with the original Java logic.
     */
    fun crc(buffer: ByteArray): Int {
        val count = buffer.size
        var ptr = 0

        // First 16-bit word
        var crc = readWord(buffer, ptr)
        ptr += 2

        if (count > 2) {
            var remainingWords = count / 2

            repeat(remainingWords) { idx ->
                val word = when {
                    idx < remainingWords - 1 ->
                        readWord(buffer, ptr).also { ptr += 2 }

                    count % 2 == 1 ->
                        ((buffer[count - 1].toInt() and 0xFF) shl 8) and 0xFFFF

                    else -> 0
                }

                crc = mixWord(crc, word)
            }
        }

        return crc and 0xFFFF
    }

    /** Reads two bytes as an unsigned 16-bit big-endian value. */
    private fun readWord(buf: ByteArray, index: Int): Int =
        ((buf[index].toInt() and 0xFF) shl 8) or
                (buf[index + 1].toInt() and 0xFF)

    /**
     * Mixes a 16-bit word into the CRC (16 shift cycles with polynomial).
     */
    private fun mixWord(initialCrc: Int, word: Int): Int {
        var crc = initialCrc and 0xFFFF
        var valWork = word and 0xFFFF

        repeat(16) {
            val carryCrc = crc and BITF != 0
            val carryVal = valWork and BITF != 0

            crc = ((crc shl 1) and 0xFFFF) + if (carryVal) 1 else 0
            if (carryCrc) crc = crc xor POLY

            valWork = (valWork shl 1) and 0xFFFF
        }

        return crc
    }
}