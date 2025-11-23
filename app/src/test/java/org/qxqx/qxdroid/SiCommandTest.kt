package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test

class SiCommandTest {

    private fun fromHex(hexString: String): ByteArray {
        val hexstr = hexString.replace(" ", "")
        require(hexstr.length % 2 == 0) { "Hex string must have an even length" }
        return hexstr.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    @Test
    fun `toSiCommand should parse SiCardDetected frame`() {
        val testCases = listOf(
            // Example expected values — replace with your real ones
            "02 e5 06 00 04 00 00 10 e9 37 8c 03" to SiCardDetected(
                cardSerie = CardSerie.CARD_5,
                stationNumber = 4u,
                cardNumber = 4329uL
            ),

            "02 e8 06 00 04 01 16 f5 7f af e2 03" to SiCardDetected(
                cardSerie = CardSerie.CARD_9,
                stationNumber = 4u,
                cardNumber = 1504639uL
            )
        )

        testCases.forEach { (hexString, expected) ->
            val frame = DataFrame.fromData(fromHex(hexString))
            val result = toSiCommand(frame)

            assertEquals("Failed frame: $hexString", expected, result)

        }
    }

    @Test
    fun `toSiCommand should parse SiCardRemoved frame`() {
        // GIVEN 02 e7 06 00 04 00 00 10 e9 17 80 03
        val frame = DataFrame.fromData(fromHex("02 e7 06 00 04 00 16 f5 7f cb c3 03"))
        val expected = SiCardRemoved(
            cardSerie = CardSerie.CARD_9,
            stationNumber = 4u,
            cardNumber = 1504639uL
        )
        val result = toSiCommand(frame)
        assertEquals(expected, result)
    }

    @Test
    fun `toSiCommand should return null for unknown command`() {
        // GIVEN
        val frame = DataFrame(
            command = 0xFF,
            data = byteArrayOf(),
            ok = true
        )

        // WHEN
        val result = toSiCommand(frame)

        // THEN
        assertNull(result)
    }

    @Test
    fun `toSiCommand should return null for not-ok frame`() {
        // GIVEN
        val frame = DataFrame(
            command = 0xE7,
            data = fromHex("07010212345678"),
            ok = false // CRC check failed
        )

        // WHEN
        val result = toSiCommand(frame)

        // THEN
        assertNull(result)
    }

    @Test
    fun `toSiCommand should return null for insufficient data`() {
        // GIVEN
        val frame = DataFrame(
            command = 0xE7,
            data = fromHex("070102"), // Not enough data
            ok = true
        )

        // WHEN
        val result = toSiCommand(frame)

        // THEN
        assertNull(result)
    }

    @Test
    fun `toSiCommand should return null for unknown card type`() {
        // GIVEN
        val frame = DataFrame(
            command = 0xE7,
            data = fromHex("00010212345678"), // Invalid card type
            ok = true
        )

        // WHEN
        val result = toSiCommand(frame)

        // THEN
        assertNull(result)
    }
}
/* card 8 read out
00 0000 02 ef 83 00 04 00 aa d6 49 94 ea ea ea ea 0c 03
01 0010 9b f2 ee ee ee ee 0d 03 29 81 00 32 02 1b 01 16
02 0020 f5 7f ff ff 3a ab 3b 3b ee ee 00 00 00 00 00 00
03 0030 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0c 32
04 0040 a8 5f 0d 32 12 1c ee ee ee ee ee ee ee ee ee ee
05 0050 ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee
06 0060 ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee
07 0070 ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee
08 0080 ee ee ee ee ee ee ae a5 03
 */