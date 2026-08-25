package org.qxqx.qxdroid.bt

import org.qxqx.qxdroid.si.CardKind
import org.qxqx.qxdroid.si.SiCard
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiCmd
import org.qxqx.qxdroid.si.SiPunch
import org.qxqx.qxdroid.si.SiReadOut
import java.util.UUID

/** Decodes the Reader BT GATT messages described in SPORTident's Reader BT API. */
class BtleSiProtocolDecoder(
    private val onReadOut: (SiReadOut) -> Unit,
) {
    private val reassemblers = mutableMapOf<UUID, MessageReassembler>()

    fun onNotification(characteristicUuid: UUID, data: ByteArray) {
        val message = reassemblers.getOrPut(characteristicUuid, ::MessageReassembler).feed(data) ?: return
        if (message.size < HEADER_SIZE) return

        val messageId = message.u16Le(0)
        val payloadLength = message.u16Le(2)
        if (message.size < HEADER_SIZE + payloadLength) return
        val payload = message.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength)

        when (messageId) {
            CARD_STATE_CHANGE -> decodeCardState(payload)
            CARD_READOUT_MINIMAL, CARD_READOUT_COMPLETE -> decodeCardReadout(payload)
        }
    }

    private fun decodeCardState(payload: ByteArray) {
        if (payload.size < 7) return
        val cardNumber = payload.u32Le(0)
        val state = payload[4].toInt() and 0xff
        val stationNumber = payload.u16Le(5).toUInt()
        val readOut = if (state == CARD_INSERTED) {
            SiReadOut.CardDetected(SiCardDetected(SiCmd.CARD_DETECTED_89pt, stationNumber, 0, cardNumber))
        } else {
            SiReadOut.CardRemoved(SiCardRemoved(stationNumber, 0, cardNumber))
        }
        onReadOut(readOut)
    }

    private fun decodeCardReadout(payload: ByteArray) {
        if (payload.size < 7) return
        val cardNumber = payload.u32Le(0)
        val family = payload[4].toInt() and 0xff
        val punchCount = payload.u16Le(5)
        val punchesEnd = 7 + punchCount * PUNCH_SIZE
        if (payload.size < punchesEnd) return

        var checkTime = NO_TIME
        var startTime = NO_TIME
        var finishTime = NO_TIME
        val punches = ArrayList<SiPunch>(punchCount)
        repeat(punchCount) { index ->
            val offset = 7 + index * PUNCH_SIZE
            val punchType = payload[offset + 1].toInt() and 0xff
            val controlCode = payload.u16Le(offset + 2)
            val time = (payload.u32Le(offset + 4) / 1_000).toInt()
            when (punchType) {
                CHECK -> checkTime = time
                START, START_RESERVE -> startTime = time
                FINISH, FINISH_RESERVE -> finishTime = time
                else -> punches += SiPunch(controlCode, time)
            }
        }

        val kind = if (family == CardKind.SIAC.code.toInt()) {
            CardKind.fromCardNumber(cardNumber)
        } else {
            CardKind.fromCode(family.toUInt()) ?: CardKind.fromCardNumber(cardNumber)
        }
        onReadOut(
            SiReadOut.Card(
                SiCard(kind, cardNumber, checkTime, startTime, finishTime, punches.toTypedArray())
            )
        )
    }

    private inner class MessageReassembler {
        private var expectedLength = 0
        private val buffer = ArrayList<Byte>()

        fun feed(raw: ByteArray): ByteArray? {
            if (raw.size < HEADER_SIZE) return null
            val messageId = raw.u16Le(0)
            val payloadLength = raw.u16Le(2)
            if (raw.size < HEADER_SIZE + payloadLength) return null
            if (messageId != WRAPPER_MESSAGE) return raw.copyOf(HEADER_SIZE + payloadLength)

            val payload = raw.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength)
            if (payload.isEmpty()) return null
            return when (payload[0].toInt() and 0xff) {
                FIRST_PACKET -> {
                    if (payload.size < 5) return null
                    expectedLength = payload.u32Le(1).toInt()
                    buffer.clear()
                    buffer.addAll(payload.copyOfRange(5, payload.size).toList())
                    null
                }
                MIDDLE_PACKET -> {
                    buffer.addAll(payload.copyOfRange(1, payload.size).toList())
                    null
                }
                LAST_PACKET -> {
                    buffer.addAll(payload.copyOfRange(1, payload.size).toList())
                    val result = if (expectedLength > 0 && buffer.size == expectedLength) buffer.toByteArray() else null
                    buffer.clear()
                    expectedLength = 0
                    result
                }
                else -> null
            }
        }
    }

    private fun ByteArray.u16Le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32Le(offset: Int): Long =
        u16Le(offset).toLong() or (u16Le(offset + 2).toLong() shl 16)

    private companion object {
        const val HEADER_SIZE = 4
        const val PUNCH_SIZE = 8
        const val WRAPPER_MESSAGE = 0xA101
        const val CARD_STATE_CHANGE = 0x1101
        const val CARD_READOUT_MINIMAL = 0x1102
        const val CARD_READOUT_COMPLETE = 0x1103
        const val CARD_INSERTED = 1
        const val CHECK = 2
        const val START = 3
        const val START_RESERVE = 4
        const val FINISH = 5
        const val FINISH_RESERVE = 6
        const val FIRST_PACKET = 1
        const val MIDDLE_PACKET = 0
        const val LAST_PACKET = 2
        const val NO_TIME = 61166
    }
}
