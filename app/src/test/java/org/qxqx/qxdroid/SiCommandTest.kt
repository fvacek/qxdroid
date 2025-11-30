package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test
import org.qxqx.qxdroid.si.CardKind
import org.qxqx.qxdroid.si.GetSiCardResp
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.parseCard5Data
import org.qxqx.qxdroid.si.parseCard9Data
import org.qxqx.qxdroid.si.toSiRecCommand

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
    fun `empty card 5 read out`() {
        val data = """
        02
        B182
        0004
        AA2A000110E901000000000000000000
        6510E9EEEE014C0156EEEE2801FA0007
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        E243
        03
        """.trimIndent()
        val frame = SiDataFrame.fromData(bytesFromHex(data))
        val resp = toSiRecCommand(frame)
        assert(resp is GetSiCardResp)
        val card = parseCard5Data((resp as GetSiCardResp).data)
        assertEquals(card.cardNumber.toInt(), 4329)
        assertEquals(card.punches.size, 0)
    }

    @Test
    fun `card 9 read out`() {
        val block_0 = """
        02EF83
        0004
        00
        5AB94994
        EAEAEAEA
        18019A6C
        18049B1B
        18039F0B
        0066061E
        0116F57E
        FFFF7D24
        3B3BEEEE
        00000000
        00000000
        00000000
        00000000
        00000000
        18649BB4
        18659C4C
        18679D2A
        186A9DCD
        18689E16
        18669EE7
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        017003
        """.trimIndent()
        val frame = SiDataFrame.fromData(bytesFromHex(block_0))
        val resp = toSiRecCommand(frame)
        assert(resp is GetSiCardResp)
        val card = parseCard9Data(null, 0, (resp as GetSiCardResp).data)
        assertEquals(1504638, card.cardNumber.toInt())
        assertEquals(6, card.punches.size)
        assertEquals("10:58:52", timeToString(card.checkTime))
        assertEquals("11:01:47", timeToString(card.startTime))
        assertEquals("11:18:35", timeToString(card.finishTime))
        assertEquals(100, card.punches[0].code)
        assertEquals("11:04:20", timeToString(card.punches[0].time))
    }
}

