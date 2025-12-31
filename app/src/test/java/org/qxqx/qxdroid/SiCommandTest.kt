package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test
import org.qxqx.qxdroid.si.CardKind
import org.qxqx.qxdroid.si.SiCard
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.SiProtocolDecoder
import org.qxqx.qxdroid.si.toSiRecCommand

class SiCommandTest {

    @Test
    fun `toSiCommand should parse SiCardDetected frame`() {
        val testCases = listOf(
            // Example expected values — replace with your real ones
            "02 e5 06 00 04 00 00 10 e9 37 8c 03" to SiCardDetected(
                cardKind = CardKind.CARD_5,
                stationNumber = 4u,
                cardNumber = 4329uL
            ),

            "02 e8 06 00 04 01 16 f5 7f af e2 03" to SiCardDetected(
                cardKind = CardKind.CARD_9,
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
        val frame_data1 = "02E50600010004907BB52103"
        val frame_data2 = """
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
        var readCard: SiCard? = null
        val decoder = SiProtocolDecoder(
            sendSiFrame = {},
            onCardReadPrivate = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frame_data1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frame_data2)))
        assert(readCard != null)
        assertEquals(readCard!!.cardNumber, 4329)
        assertEquals(readCard.punches.size, 0)
    }

    @Test
    fun `real card 5 read out`() {
        val frameData1 = "02E50600010004907BB52103"
        val frameData2 = """
        02
        B182
        0001
        AAFEFEFE907B04000000000000000000
        65907B49954A700856EEEE28040F0007
        002649BA2349DE364A01414A17374A3C
        002E4A54204A6B00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        0000EEEE00EEEE00EEEE00EEEE00EEEE
        795D
        03
        """.trimIndent()
        var readCard: SiCard? = null
        val decoder = SiProtocolDecoder(
            sendSiFrame = {},
            onCardReadPrivate = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData2)))
        assert(readCard != null)
        assertEquals(436987, readCard!!.cardNumber)
        assertEquals(7, readCard.punches.size)
    }

    @Test
    fun `card 9 read out`() {
        val frame_data1 = "02E50600010004907BB52103"
        val frame_data2 = """
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
        var readCard: SiCard? = null
        val decoder = SiProtocolDecoder(
            sendSiFrame = {},
            onCardReadPrivate = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frame_data1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frame_data2)))
        assert(readCard != null)
        assertEquals(1504638, readCard!!.cardNumber)
        assertEquals(6, readCard.punches.size)
        assertEquals("10:58:52", timeToString(readCard.checkTime))
        assertEquals("11:01:47", timeToString(readCard.startTime))
        assertEquals("11:18:35", timeToString(readCard.finishTime))
        assertEquals(100, readCard.punches[0].code)
        assertEquals("11:04:20", timeToString(readCard.punches[0].time))
    }
}

