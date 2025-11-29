package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test

class SiCommandTest {

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
            val frame = SiDataFrame.fromData(bytesFromHex(hexString))
            val result = toSiRecCommand(frame)

            assertEquals("Failed frame: $hexString", expected, result)

        }
    }

    @Test
    fun `toSiCommand should parse SiCardRemoved frame`() {
        // GIVEN 02 e7 06 00 04 00 00 10 e9 17 80 03
        val frame = SiDataFrame.fromData(bytesFromHex("02 e7 06 00 04 00 16 f5 7f cb c3 03"))
        val expected = SiCardRemoved(
            cardSerie = CardSerie.CARD_5,
            stationNumber = 4u,
            cardNumber = 1504639uL
        )
        val result = toSiRecCommand(frame)
        assertEquals(expected, result)
    }

    @Test
    fun `card 8 read out`() {
        val data = """
        02 ef 83 00 04 00 aa d6 49 94 ea ea ea ea 0c 03
        9b f2 ee ee ee ee 0d 03 29 81 00 32 02 1b 01 16
        f5 7f ff ff 3a ab 3b 3b ee ee 00 00 00 00 00 00
        00 00 00 00 00 00 00 00 00 00 00 00 00 00 0c 32
        a8 5f 0d 32 12 1c ee ee ee ee ee ee ee ee ee ee
        ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee
        ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee
        ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee ee
        ee ee ee ee ee ee ae a5 03
        """.trimIndent()
        val frame = SiDataFrame.fromData(bytesFromHex(data))
        val expected = GetSiCard5Resp(
            stationNumber = 4u,
            data = TODO(),
        )
        val result = toSiRecCommand(frame)
        assertEquals(expected, result)
    }
}
