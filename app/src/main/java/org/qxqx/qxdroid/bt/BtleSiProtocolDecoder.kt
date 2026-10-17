package org.qxqx.qxdroid.bt

import org.qxqx.qxdroid.si.CardKind
import org.qxqx.qxdroid.si.SiCard
import org.qxqx.qxdroid.si.SiCardDetected
import org.qxqx.qxdroid.si.SiCardRemoved
import org.qxqx.qxdroid.si.SiCmd
import org.qxqx.qxdroid.si.SiPunch
import org.qxqx.qxdroid.si.SiReadOut
import org.qxqx.qxdroid.si.SiacBatteryStatus
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID

/** Decodes the Reader BT GATT messages described in SPORTident's Reader BT API. */
class BtleSiProtocolDecoder(
    private val onReadOut: (SiReadOut) -> Unit,
    private val sendBleCommand: ((uuid: UUID, data: ByteArray) -> Unit)? = null,
) {
    private val reassemblers = mutableMapOf<UUID, MessageReassembler>()

    fun onNotification(characteristicUuid: UUID, data: ByteArray) {
        Timber.d("Reader BT decoding notification uuid=$characteristicUuid bytes=${data.size}")
        val message = reassemblers.getOrPut(characteristicUuid, ::MessageReassembler).feed(data) ?: return
        if (message.size < HEADER_SIZE) return

        val messageId = message.u16Le(0)
        val payloadLength = message.u16Le(2)
        if (message.size < HEADER_SIZE + payloadLength) return
        val payload = message.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadLength)

        Timber.d("Reader BT message id=0x${messageId.toString(16)} payloadLength=$payloadLength")
        when (messageId) {
            CARD_STATE_CHANGE -> decodeCardState(payload)
            CARD_READOUT_MINIMAL, CARD_READOUT_COMPLETE -> decodeCardReadout(messageId, payload)
            else -> Timber.w("Reader BT unknown message id=0x${messageId.toString(16)}")
        }
    }

    private fun decodeCardState(payload: ByteArray) {
        if (payload.size < 7) return
        val cardNumber = payload.u32Le(0)
        val state = payload[4].toInt() and 0xff
        val stationNumber = payload.u16Le(5).toUInt()
        Timber.i("Reader BT card state: card=$cardNumber state=$state station=$stationNumber")
        val readOut = if (state == CARD_INSERTED) {
            sendBleCommand?.invoke(CARD_DATA_UUID, REQUEST_CARD_READOUT_MSG)
            SiReadOut.CardDetected(SiCardDetected(SiCmd.CARD_DETECTED_89pt, stationNumber, 0, cardNumber))
        } else {
            SiReadOut.CardRemoved(SiCardRemoved(stationNumber, 0, cardNumber))
        }
        onReadOut(readOut)
    }

    private fun decodeCardReadout(messageId: Int, payload: ByteArray) {
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
            val timeInMs = payload.u32Le(offset + 4)
            val time = parseReaderBtTime(timeInMs)

            when (punchType) {
                CHECK -> if (checkTime == NO_TIME) checkTime = time
                START, START_RESERVE -> if (startTime == NO_TIME) startTime = time
                FINISH, FINISH_RESERVE -> if (finishTime == NO_TIME) finishTime = time
                CONTROL -> punches += SiPunch(controlCode, time)
                CLEAR -> { /* Skip clear record from course punches */ }
                else -> {
                    if (controlCode > 0) {
                        punches += SiPunch(controlCode, time)
                    }
                }
            }
        }

        val kind = if (family == CardKind.SIAC.code.toInt()) {
            CardKind.fromCardNumber(cardNumber)
        } else {
            CardKind.fromCode(family.toUInt()) ?: CardKind.fromCardNumber(cardNumber)
        }

        val card = SiCard(
            kind, cardNumber, checkTime, startTime, finishTime, punches.toTypedArray()
        )

        // Parse SIAC battery/production data if present
        if (messageId == CARD_READOUT_COMPLETE && family == CardKind.SIAC.code.toInt() &&
            cardNumber in 8000000L..8999999L && payload.size >= punchesEnd + 13
        ) {
            val batteryMv = payload.u16Le(punchesEnd + 6)
            val batteryYear = payload[punchesEnd + 8].toInt() and 0xff
            val batteryMonth = payload[punchesEnd + 9].toInt() and 0xff
            val batteryDay = payload[punchesEnd + 10].toInt() and 0xff
            val voltage = batteryMv / 1000.0
            val isLow = batteryMv < 2700
            val year = if (batteryYear in 0..99) 2000 + batteryYear else 2000
            val month = batteryMonth.coerceIn(1, 12)
            val day = batteryDay.coerceIn(1, 31)
            val replaceDate = try {
                LocalDate.of(year, month, day)
            } catch (_: Exception) {
                LocalDate.now()
            }
            card.baterry = SiacBatteryStatus(voltage, isLow, replaceDate)
        }

        onReadOut(SiReadOut.Card(card))

        sendBleCommand?.invoke(CARD_DATA_UUID, FEEDBACK_OK_MSG)
    }

    private fun parseReaderBtTime(timeInMs: Long): Int {
        if (timeInMs == INVALID_TIME_MS || timeInMs == INVALID_TIME_MS_ALT || timeInMs < 0 || timeInMs >= 86_400_000L * 7) {
            return NO_TIME
        }
        val timeOfDayMs = timeInMs % 86_400_000L
        return (timeOfDayMs / 1000L).toInt()
    }

    private inner class MessageReassembler {
        private var expectedLength = 0
        private var directExpectedLength = 0
        private val buffer = ArrayList<Byte>()

        fun feed(raw: ByteArray): ByteArray? {
            // Some Reader BT firmware sends a large normal message directly in
            // MTU-sized notifications instead of using the documented wrapper.
            if (directExpectedLength > 0) {
                buffer.addAll(raw.toList())
                if (buffer.size < directExpectedLength) return null
                val result = buffer.take(directExpectedLength).toByteArray()
                buffer.clear()
                directExpectedLength = 0
                return result
            }

            if (raw.size < HEADER_SIZE) return null
            val messageId = raw.u16Le(0)
            val payloadLength = raw.u16Le(2)
            val messageLength = HEADER_SIZE + payloadLength
            if (messageId != WRAPPER_MESSAGE) {
                if (raw.size >= messageLength) return raw.copyOf(messageLength)
                directExpectedLength = messageLength
                buffer.clear()
                buffer.addAll(raw.toList())
                return null
            }
            if (raw.size < messageLength) return null

            val payload = raw.copyOfRange(HEADER_SIZE, messageLength)
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
                    val result = if (expectedLength > 0 && buffer.size >= expectedLength) buffer.take(expectedLength).toByteArray() else null
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

    companion object {
        val CARD_DATA_UUID: UUID = UUID.fromString("bd510013-6aec-4628-a146-f3e95bc49e62")
        val SETTINGS_WRITE_UUID: UUID = UUID.fromString("bd510003-6aec-4628-a146-f3e95bc49e62")

        val REQUEST_CARD_READOUT_MSG = byteArrayOf(0x03.toByte(), 0x11.toByte(), 0x00, 0x00)
        val FEEDBACK_OK_MSG = byteArrayOf(0x10.toByte(), 0x11.toByte(), 0x01, 0x00, 0x01)
        val SET_AUTOSEND_ON_MSG = byteArrayOf(0x0F.toByte(), 0x01.toByte(), 0x01, 0x00, 0x01)

        const val HEADER_SIZE = 4
        const val PUNCH_SIZE = 8
        const val WRAPPER_MESSAGE = 0xA101
        const val CARD_STATE_CHANGE = 0x1101
        const val CARD_READOUT_MINIMAL = 0x1102
        const val CARD_READOUT_COMPLETE = 0x1103
        const val CARD_INSERTED = 1
        const val CLEAR = 1
        const val CHECK = 2
        const val START = 3
        const val START_RESERVE = 4
        const val FINISH = 5
        const val FINISH_RESERVE = 6
        const val CONTROL = 7
        const val FIRST_PACKET = 1
        const val MIDDLE_PACKET = 0
        const val LAST_PACKET = 2
        const val NO_TIME = 61166
        const val INVALID_TIME_MS = 0xFFFFFFFFL
        const val INVALID_TIME_MS_ALT = 0xEEEEEEEE
    }
}
