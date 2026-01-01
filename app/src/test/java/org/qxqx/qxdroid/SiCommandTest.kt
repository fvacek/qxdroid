package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test
import org.qxqx.qxdroid.si.SiCard
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiCmd
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.SiProtocolDecoder
import org.qxqx.qxdroid.si.toSiRecCommand

class SiCommandTest {

    @Test
    fun `toSiCommand should parse SiCardDetected frame`() {
        val testCases = listOf(
            // Example expected values — replace with your real ones
            "02 e5 06 00 04 00 00 10 e9 37 8c 03" to SiCardDetected(
                command = SiCmd.CARD_DETECTED_5,
                stationNumber = 4u,
                cardSerie = 0,
                cardNumber = 4329uL
            ),

            "02 e8 06 0004 01 16f57f afe2 03" to SiCardDetected(
                command = SiCmd.CARD_DETECTED_89pt,
                stationNumber = 4u,
                cardSerie = 1,
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
        val frame = SiDataFrame.fromData(bytesFromHex("02 e7 06 0004 00 16f57f cbc3 03"))
        val expected = SiCardRemoved(
            stationNumber = 4u,
            cardSerie = 0,
            cardNumber = 1504639uL
        )
        val result = toSiRecCommand(frame)
        assertEquals(expected, result)
    }

    @Test
    fun `empty card 5 read out`() {
        val frameData1 = "02E50600010004907BB52103"
        val frameData2 = """
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
            onCardRead = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData2)))
        assert(readCard != null)
        assertEquals(readCard!!.cardNumber, 4329)
        assertEquals(readCard.punches.size, 0)
    }

    @Test
    fun `real card 5 read out`() {
        val frameData1 = "02 E5 06 0001 00 04907B B521 03"
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
            onCardRead = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData2)))
        assert(readCard != null)
        assertEquals(436987, readCard!!.cardNumber)
        assertEquals(7, readCard.punches.size)
    }

    //@Test
    //fun `card 6 read out`() {
    //    val frameData1 = "02 E6 06 0001 00 0EE480 BFBF 03"
    //    var readCard: SiCard? = null
    //    val decoder = SiProtocolDecoder(
    //        sendSiFrame = {},
    //        onCardRead = { card -> readCard = card }
    //    )
    //    decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData1)))
    //    assert(readCard != null)
    //}

    @Test
    fun `card 9 read out`() {
        val frameData1 = "02 E8 06 0004 01 16F57F AFE2 03"
        val frameData2 = """
        02 EF 83 0004 00
        AAD64994
        EAEAEAEA
        0A168B85
        0A038B87
        0A03998A
        001F15A3
        0116F57F
        FFFF3AAB
        3B3BEEEE
        00000000
        00000000
        00000000
        00000000
        00000000
        0A308D90
        0A338E0B
        0A2F8E8C
        0A288F40
        0A328F8A
        0A319141
        0A2B92B7
        0A2A939C
        0A21942B
        0A27945D
        0A2994F8
        0A22950B
        0A209622
        0A269697
        0A2C9727
        0A249841
        0A2E9868
        0A3498A4
        7162
        03
        """.trimIndent()
        val frameData3 = """
        02 EF 83 0004 01 
        0A2D98C4
        0A2598DD
        0A1F9977
        EEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE
        C23F
        03
        """.trimIndent()
        var readCard: SiCard? = null
        val decoder = SiProtocolDecoder(
            sendSiFrame = {},
            onCardRead = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData2)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData3)))
        assert(readCard != null)
        assertEquals(1504639, readCard!!.cardNumber)
        assertEquals(21, readCard.punches.size)
        assertEquals("09:55:17", timeToString(readCard.checkTime))
        assertEquals("09:55:19", timeToString(readCard.startTime))
        assertEquals("10:55:06", timeToString(readCard.finishTime))
        assertEquals(48, readCard.punches[0].code)
        assertEquals("10:04:00", timeToString(readCard.punches[0].time))
        assertEquals(51, readCard.punches[1].code)
        assertEquals("10:06:03", timeToString(readCard.punches[1].time))
        assertEquals(31, readCard.punches[20].code)
        assertEquals("10:54:47", timeToString(readCard.punches[20].time))
    }
}

