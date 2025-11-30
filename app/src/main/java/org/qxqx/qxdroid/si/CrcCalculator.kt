package org.qxqx.qxdroid.si

object CrcCalculator {

    private const val POLY = 0x8005
    private const val BITF = 0x8000

    fun crc(buffer: ByteArray): Int {
        // Handle edge cases for small buffers
        if (buffer.size < 2) {
            return if (buffer.isEmpty()) 0 else (buffer[0].toInt() and 0xFF shl 8)
        }

        val initialCrc = readWord(buffer, 0)
        
        // Build a list of the remaining words to be mixed into the CRC.
        // This precisely replicates the logic of the original imperative loop.
        val wordsToMix = buildList {
            var ptr = 2
            // Add all the full words from the middle of the buffer
            while (ptr < buffer.size - 1) {
                add(readWord(buffer, ptr))
                ptr += 2
            }
            // Handle the final word according to the original's logic
            if (buffer.size % 2 == 1) {
                // If the size is odd, the last word is formed from the last byte
                add((buffer.last().toInt() and 0xFF) shl 8)
            } else {
                // If the size is even (and > 2), the last word is always zero
                add(0)
            }
        }

        // Use fold to apply the mixing function across the words
        val finalCrc = wordsToMix.fold(initialCrc) { currentCrc, word ->
            mixWord(currentCrc, word)
        }

        return finalCrc and 0xFFFF
    }

    /** Reads two bytes as an unsigned 16-bit big-endian value. */
    private fun readWord(buf: ByteArray, index: Int): Int =
        ((buf[index].toInt() and 0xFF) shl 8) or
                (buf[index + 1].toInt() and 0xFF)

    /**
     * Mixes a 16-bit word into the CRC (16 shift cycles with polynomial).
     */
    private fun mixWord(initialCrc: Int, word: Int): Int {
        var currentCrc = initialCrc and 0xFFFF
        var currentWord = word and 0xFFFF

        repeat(16) {
            val crcMsbSet = (currentCrc and BITF) != 0
            val wordMsbSet = (currentWord and BITF) != 0

            // Shift CRC left and bring in the word's MSB
            currentCrc = ((currentCrc shl 1) and 0xFFFF) + if (wordMsbSet) 1 else 0
            
            // If CRC's original MSB was set, XOR with polynomial
            if (crcMsbSet) {
                currentCrc = currentCrc xor POLY
            }

            // Shift word left to process the next bit
            currentWord = (currentWord shl 1) and 0xFFFF
        }

        return currentCrc
    }
}