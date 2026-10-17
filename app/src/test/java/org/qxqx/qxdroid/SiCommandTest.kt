package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test
import org.qxqx.qxdroid.si.CardKind
import org.qxqx.qxdroid.si.SiCard
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiCmd
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.si.SiReadOut
import org.qxqx.qxdroid.si.UsbSiProtocolDecoder
import org.qxqx.qxdroid.si.toSiRecCommand
import java.util.UUID

class SiCommandTest {

    @Test
    fun `toSiCommand should parse SiCardDetected frame`() {
        val testCases = listOf(
            // Example expected values — replace with your real ones
            "02 e5 06 00 04 00 00 10 e9 37 8c 03" to SiCardDetected(
                command = SiCmd.CARD_DETECTED_5,
                stationNumber = 4u,
                cardSerie = 0,
                cardNumber = 4329L
            ),

            "02 e8 06 0004 01 16f57f afe2 03" to SiCardDetected(
                command = SiCmd.CARD_DETECTED_89pt,
                stationNumber = 4u,
                cardSerie = 1,
                cardNumber = 1504639L
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
            cardNumber = 1504639L
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
        val decoder = UsbSiProtocolDecoder(
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
        val decoder = UsbSiProtocolDecoder(
            sendSiFrame = {},
            onCardRead = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData2)))
        assert(readCard != null)
        assertEquals(436987, readCard!!.cardNumber)
        assertEquals(7, readCard.punches.size)
    }

    @Test
    fun `card 6 read out`() {
        val frameData1 = "02 E6 06 0001 00 0EE480 BFBF 03"
        val block0 = """
        02
        E183
        0001
        00
        01 01 01 01 ED ED ED ED 55 AA 00 0E E4 80 F3 B7
        00 22 07 08 07 07 4B 7C 07 05 4B 51 EE EE EE EE
        07 01 4B 4B FF FF FF FF 00 00 00 01 20 20 20 20    
        50 61 74 65 6B 20 20 20 20 20 20 20 20 20 20 20           
        20 20 20 20 52 69 63 68 61 72 64 20 28 41 72 6E
        6F 73 74 29 20 20 20 20 43 5A 45 20 20 20 20 20     
        20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20                 
        20 20 20 20 20 20 20 20 20 20 20 20 20 20 20 20
        2A 3B
        03
        """.trimIndent()
        val block6 = """
        02
        E183
        0001
        06
        07 3B 4B 58 07 3F 4B 5C 07 30 4B 63 07 27 4B 6A
        07 35 4B 73 07 32 4B 74 07 22 4B 79 EE EE EE EE
        EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE
        EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE
        EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE
        EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE
        EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE
        EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE EE
        E6 83
        03
        """.trimIndent()
        var readCard: SiCard? = null
        val decoder = UsbSiProtocolDecoder(
            sendSiFrame = { frame -> println("Sending: $frame") },
            onCardRead = { card -> readCard = card }
        )
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(frameData1)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(block0)))
        decoder.onDataFrame(SiDataFrame.fromData(bytesFromHex(block6)))
        assert(readCard != null)
        assertEquals(976000, readCard!!.cardNumber)
        assertEquals(7, readCard.punches.size)
        assertEquals("--:--:--", timeToString(readCard.checkTime))
        assertEquals("05:21:21", timeToString(readCard.startTime))
        assertEquals("05:22:04", timeToString(readCard.finishTime))
        assertEquals(59, readCard.punches[0].code)
        assertEquals("05:21:28", timeToString(readCard.punches[0].time))
        assertEquals(63, readCard.punches[1].code)
        assertEquals("05:21:32", timeToString(readCard.punches[1].time))
        assertEquals(34, readCard.punches[readCard.punches.size - 1].code)
        assertEquals("05:22:01", timeToString(readCard.punches[readCard.punches.size - 1].time))
    }

    @Test
    fun `card 6 reads 33 punches across blocks 6 and 7`() {
        val sentFrames = mutableListOf<SiDataFrame>()
        var readCard: SiCard? = null
        val decoder = UsbSiProtocolDecoder(
            sendSiFrame = { sentFrames.add(it) },
            onCardRead = { card -> readCard = card }
        )
        val punches = (1..33).map { code -> code to (1_000 + code) }

        decoder.onDataFrame(card6Response(0, card6FirstBlock(punches.size)))
        decoder.onDataFrame(card6Response(6, card6PunchBlock(punches.take(32))))
        decoder.onDataFrame(card6Response(7, card6PunchBlock(punches.drop(32))))

        assertNotNull(readCard)
        assertEquals(punches, readCard!!.punches.map { it.code to it.time })
        assertEquals(
            listOf(6, 7),
            sentFrames.filter { it.command == SiCmd.GET_CARD_6.code }.map { it.data.single().toInt() }
        )
    }

    @Test
    fun `card 6 reads 64 punches without overwriting the first block`() {
        val sentFrames = mutableListOf<SiDataFrame>()
        var readCard: SiCard? = null
        val decoder = UsbSiProtocolDecoder(
            sendSiFrame = { sentFrames.add(it) },
            onCardRead = { card -> readCard = card }
        )
        val punches = (1..64).map { code -> code to (2_000 + code) }

        decoder.onDataFrame(card6Response(0, card6FirstBlock(punches.size)))
        decoder.onDataFrame(card6Response(6, card6PunchBlock(punches.take(32))))
        decoder.onDataFrame(card6Response(7, card6PunchBlock(punches.drop(32))))

        assertNotNull(readCard)
        assertEquals(punches, readCard!!.punches.map { it.code to it.time })
        assertEquals(
            listOf(6, 7),
            sentFrames.filter { it.command == SiCmd.GET_CARD_6.code }.map { it.data.single().toInt() }
        )
    }

    private fun card6Response(blockNumber: Int, blockData: ByteArray): SiDataFrame {
        val responseData = ByteArray(131)
        responseData[2] = blockNumber.toByte()
        blockData.copyInto(responseData, destinationOffset = 3)
        return SiDataFrame(SiCmd.GET_CARD_6.code, responseData)
    }

    private fun card6FirstBlock(punchCount: Int): ByteArray = ByteArray(128).also { data ->
        data[8] = CardKind.CARD_6.code.toByte()
        data[11] = 0x0E
        data[12] = 0xE4.toByte()
        data[13] = 0x80.toByte()
        data[18] = punchCount.toByte()
    }

    private fun card6PunchBlock(punches: List<Pair<Int, Int>>): ByteArray = ByteArray(128).also { data ->
        punches.forEachIndexed { index, (code, time) ->
            val offset = index * 4
            data[offset + 1] = code.toByte()
            data[offset + 2] = (time shr 8).toByte()
            data[offset + 3] = time.toByte()
        }
    }

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
        val decoder = UsbSiProtocolDecoder(
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

    @Test
    fun `BtleSiProtocolDecoder should decode CardStateChange and trigger readout request`() {
        val readOuts = mutableListOf<SiReadOut>()
        val sentCommands = mutableListOf<Pair<UUID, ByteArray>>()

        val decoder = org.qxqx.qxdroid.bt.BtleSiProtocolDecoder(
            onReadOut = { readOuts.add(it) },
            sendBleCommand = { uuid, data -> sentCommands.add(uuid to data) }
        )

        // 0x1101 message: Card state change, payload len 7: card=1504639 (0x16F57F), state=1 (inserted), station=4
        val payload = byteArrayOf(
            0x7F.toByte(), 0xF5.toByte(), 0x16.toByte(), 0x00.toByte(), // card 1504639
            0x01.toByte(), // inserted
            0x04.toByte(), 0x00.toByte() // station 4
        )
        val header = byteArrayOf(0x01.toByte(), 0x11.toByte(), 0x07.toByte(), 0x00.toByte())
        val frame = header + payload

        decoder.onNotification(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.CARD_DATA_UUID, frame)

        assertEquals(1, readOuts.size)
        assertTrue(readOuts[0] is SiReadOut.CardDetected)
        val detected = (readOuts[0] as SiReadOut.CardDetected).command
        assertEquals(1504639L, detected.cardNumber)
        assertEquals(4u, detected.stationNumber)

        assertEquals(1, sentCommands.size)
        assertEquals(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.CARD_DATA_UUID, sentCommands[0].first)
        assertArrayEquals(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.REQUEST_CARD_READOUT_MSG, sentCommands[0].second)
    }

    @Test
    fun `BtleSiProtocolDecoder should decode CardReadout and send feedback command`() {
        val readOuts = mutableListOf<SiReadOut>()
        val sentCommands = mutableListOf<Pair<UUID, ByteArray>>()

        val decoder = org.qxqx.qxdroid.bt.BtleSiProtocolDecoder(
            onReadOut = { readOuts.add(it) },
            sendBleCommand = { uuid, data -> sentCommands.add(uuid to data) }
        )

        // Card readout 0x1103: card=8632100 (0x83B324), family=15 (SIAC), 2 punches + SIAC prod data
        // Punch 1: Check (type=2), station=31, time = 35600000 ms (09:53:20)
        // Punch 2: Control (type=7), station=48, time = 36240000 ms (10:04:00)
        val cardNumBytes = byteArrayOf(0x24.toByte(), 0xB3.toByte(), 0x83.toByte(), 0x00.toByte())
        val familyByte = byteArrayOf(15.toByte())
        val punchCountBytes = byteArrayOf(0x02.toByte(), 0x00.toByte())

        val punch1 = byteArrayOf(
            0x00.toByte(), 0x02.toByte(), 31.toByte(), 0x00.toByte(),
            0x20.toByte(), 0xDC.toByte(), 0x1E.toByte(), 0x02.toByte() // 35600000 ms
        )
        val punch2 = byteArrayOf(
            0x00.toByte(), 0x07.toByte(), 48.toByte(), 0x00.toByte(),
            0xF0.toByte(), 0xD6.toByte(), 0x28.toByte(), 0x02.toByte() // 36240000 ms
        )

        // 13 bytes SIAC prod data:
        // hw=10, sw=20, signal=5, clear=12, battery_mv=3100 (0x0C1C), year=24, month=5, day=15, prod_year=20, prod_month=1
        val siacProdData = byteArrayOf(
            10.toByte(), 20.toByte(), 0x05.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x00.toByte(),
            0x1C.toByte(), 0x0C.toByte(), // battery 3100 mV
            24.toByte(), 5.toByte(), 15.toByte(), // battery date: 2024-05-15
            20.toByte(), 1.toByte()
        )

        val payload = cardNumBytes + familyByte + punchCountBytes + punch1 + punch2 + siacProdData
        val payloadLen = payload.size
        val header = byteArrayOf(0x03.toByte(), 0x11.toByte(), (payloadLen and 0xff).toByte(), ((payloadLen shr 8) and 0xff).toByte())
        val frame = header + payload

        decoder.onNotification(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.CARD_DATA_UUID, frame)

        assertEquals(1, readOuts.size)
        assertTrue(readOuts[0] is SiReadOut.Card)
        val card = (readOuts[0] as SiReadOut.Card).card
        assertEquals(8631076L, card.cardNumber)
        assertEquals(CardKind.SIAC, card.cardKind)
        assertEquals("09:52:56", timeToString(card.checkTime))
        assertEquals("--:--:--", timeToString(card.startTime))
        assertEquals("--:--:--", timeToString(card.finishTime))
        assertEquals(1, card.punches.size)
        assertEquals(48, card.punches[0].code)
        assertEquals("10:03:50", timeToString(card.punches[0].time))

        assertNotNull(card.baterry)
        assertEquals(3.10, card.baterry!!.voltage, 0.01)
        assertFalse(card.baterry!!.isLow)
        assertEquals("2024-05-15", card.baterry!!.replaceDate.toString())

        assertEquals(1, sentCommands.size)
        assertEquals(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.CARD_DATA_UUID, sentCommands[0].first)
        assertArrayEquals(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.FEEDBACK_OK_MSG, sentCommands[0].second)
    }

    @Test
    fun `BtleSiProtocolDecoder should handle invalid times as NO_TIME`() {
        val readOuts = mutableListOf<SiReadOut>()
        val decoder = org.qxqx.qxdroid.bt.BtleSiProtocolDecoder(
            onReadOut = { readOuts.add(it) }
        )

        // Card readout 0x1102: card=1504639, family=1, 1 punch with time_in_ms = 0xFFFFFFFF
        val cardNumBytes = byteArrayOf(0x7F.toByte(), 0xF5.toByte(), 0x16.toByte(), 0x00.toByte())
        val familyByte = byteArrayOf(1.toByte())
        val punchCountBytes = byteArrayOf(0x01.toByte(), 0x00.toByte())
        val punch1 = byteArrayOf(
            0x00.toByte(), 0x02.toByte(), 31.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte() // invalid time
        )

        val payload = cardNumBytes + familyByte + punchCountBytes + punch1
        val header = byteArrayOf(0x02.toByte(), 0x11.toByte(), payload.size.toByte(), 0x00.toByte())

        decoder.onNotification(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.CARD_DATA_UUID, header + payload)

        assertEquals(1, readOuts.size)
        val card = (readOuts[0] as SiReadOut.Card).card
        assertEquals("--:--:--", timeToString(card.checkTime))
    }

    @Test
    fun `BtleSiProtocolDecoder reassembler should assemble fragmented wrapper 0xA101 packets`() {
        val readOuts = mutableListOf<SiReadOut>()
        val decoder = org.qxqx.qxdroid.bt.BtleSiProtocolDecoder(
            onReadOut = { readOuts.add(it) }
        )

        // Build inner card readout 0x1102 frame
        val innerPayload = byteArrayOf(
            0x7F.toByte(), 0xF5.toByte(), 0x16.toByte(), 0x00.toByte(), // card 1504639
            1.toByte(), // family
            0.toByte(), 0.toByte() // 0 punches
        )
        val innerMsg = byteArrayOf(0x02.toByte(), 0x11.toByte(), innerPayload.size.toByte(), 0x00.toByte()) + innerPayload

        // Split innerMsg into 2 segment chunks
        val chunk1 = innerMsg.copyOfRange(0, 5)
        val chunk2 = innerMsg.copyOfRange(5, innerMsg.size)

        // Wrapper 0xA101 First Packet (flag=1):
        // header: 0x01 0xA1 (msgId), len (1 + 4 + chunk1.size), flag=1, total_len = innerMsg.size (4 B)
        val wrapper1PayloadLen = 1 + 4 + chunk1.size
        val wrapper1Header = byteArrayOf(0x01.toByte(), 0xA1.toByte(), wrapper1PayloadLen.toByte(), 0x00.toByte())
        val totalLenBytes = byteArrayOf(innerMsg.size.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        val wrapper1 = wrapper1Header + byteArrayOf(0x01.toByte()) + totalLenBytes + chunk1

        // Wrapper 0xA101 Last Packet (flag=2):
        // header: 0x01 0xA1 (msgId), len (1 + chunk2.size), flag=2
        val wrapper2PayloadLen = 1 + chunk2.size
        val wrapper2Header = byteArrayOf(0x01.toByte(), 0xA1.toByte(), wrapper2PayloadLen.toByte(), 0x00.toByte())
        val wrapper2 = wrapper2Header + byteArrayOf(0x02.toByte()) + chunk2

        decoder.onNotification(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.CARD_DATA_UUID, wrapper1)
        assertEquals(0, readOuts.size) // Not complete yet

        decoder.onNotification(org.qxqx.qxdroid.bt.BtleSiProtocolDecoder.CARD_DATA_UUID, wrapper2)
        assertEquals(1, readOuts.size) // Completed!
        val card = (readOuts[0] as SiReadOut.Card).card
        assertEquals(1504639L, card.cardNumber)
    }
}


