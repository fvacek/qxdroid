package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test

class SiCommandTest {

    @Test
    fun `toSiCommand should parse SiCardDetected frame`() {
        val testCases = listOf(
            // Example expected values — replace with your real ones
            "02 e5 06 00 04 00 00 10 e9 37 8c 03" to SiCardDetected(
                cardSerie = CardKind.CARD_5,
                stationNumber = 4u,
                cardNumber = 4329uL
            ),

            "02 e8 06 00 04 01 16 f5 7f af e2 03" to SiCardDetected(
                cardSerie = CardKind.CARD_9,
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
            cardSerie = CardKind.CARD_5,
            stationNumber = 4u,
            cardNumber = 1504639uL
        )
        val result = toSiRecCommand(frame)
        assertEquals(expected, result)
    }

    @Test
    fun `card 5 read out`() {
        val data = """
        02
        B182
        0004
        AA2A000110E9010000000000000000006510E9EEEE014C0156EEEE2801FA0007
        0000EEEE00EEEE00EEEE00EEEE00EEEE0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE0000EEEE00EEEE00EEEE00EEEE00EEEE
        E243
        03
        """.trimIndent()
        val frame = SiDataFrame.fromData(bytesFromHex(data))
        val result = toSiRecCommand(frame)
        assert(result is GetSiCard5Resp)
    }
}

