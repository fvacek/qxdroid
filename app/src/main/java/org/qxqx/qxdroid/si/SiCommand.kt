package org.qxqx.qxdroid.si

import org.qxqx.qxdroid.bytesFromHex
import org.qxqx.qxdroid.shv.RpcValue
import org.qxqx.qxdroid.timeToString
import java.time.LocalDate
import java.util.Date

private const val TAG = "SiCommand"

enum class SiCmd(val code: Int) {
    INVALID(0),
    STATION_BEEP(0x06),
    GET_CARD_5(0xB1),
    GET_CARD_6(0xE1),
    CARD_DETECTED_5(0xE5),
    CARD_REMOVED(0xE7),
    CARD_DETECTED_8(0xE8),
    SIAC_MEASURE_BATTERY(0xEA),
    GET_CARD_8(0xEF),
    ;

    companion object {
        fun fromCode(code: Int): SiCmd =
            entries.firstOrNull { it.code == code } ?: INVALID
    }
}

enum class CardKind(val code: UInt) {
    CARD_5(0u),
    CARD_8(2u),
    CARD_9(1u),
    PCARD(4u),
    TCARD(6u),
    SIAC(15u);

    companion object {
        fun fromCode(code: UInt): CardKind =
            entries.firstOrNull { it.code == code } ?: CARD_5
    }
}

sealed class SiRecCommand()

data class SiCardDetected(
    val cardSerie: CardKind,
    val stationNumber: UInt,
    val cardNumber: ULong,
) : SiRecCommand() {
    override fun toString(): String {
        return "Detected: $cardSerie, $cardNumber sn: $stationNumber"
    }
}

data class SiCardRemoved(
    val cardSerie: CardKind,
    val stationNumber: UInt,
    val cardNumber: ULong,
) : SiRecCommand() {
    override fun toString(): String {
        return "Removed: $cardSerie, $cardNumber sn: $stationNumber"
    }
}

data class SiPunch(
    var code: Int,
    var time: Int,
)

data class SiacBatteryStatus(
    val baterryVoltage: Double,
    val baterryLow: Boolean,
    val batteryReplaceDate: LocalDate,
)
data class SiCard(
    val cardKind: CardKind,
    val cardSerie: Int,
    val cardNumber: Int,
    val checkTime: Int,
    val startTime: Int,
    val finishTime: Int,
    val punches: Array<SiPunch>,
) : SiRecCommand() {
    var baterryStatus: SiacBatteryStatus? = null

    constructor() : this(CardKind.CARD_5, 0, 0, 0, 0, 0, arrayOf())

    override fun toString(): String {
        var punchesStr = ""
        var no = 0
        punches.forEach { punch ->
            run {
                no += 1
                punchesStr += "\n%4d. %4d    %s".format(no, punch.code.toInt(),
                    timeToString(punch.time)
                )
            }
        }
        return """---------------------------
SI CARD $cardNumber serie: $cardSerie
checkTime: ${timeToString(checkTime)}
startTime: ${timeToString(startTime)}
finishTime: ${timeToString(finishTime)}
$punchesStr
---------------------------
"""
    }
}

data class GetSiCardResp(
    val stationNumber: Int,
    val blockNumber: Int,
    val data: ByteArray
) : SiRecCommand()

class SiacMeasureBatteryVoltageResp() : SiRecCommand()

private fun parseDataLayoutCardDetectedRemoved(data: ByteArray): Triple<CardKind, UInt, ULong> {
    // Assumed layout: [stationNumber: 2 bytes BE, cardNumber: 4 bytes BE]
    if (data.size != 6) {
        throw IllegalArgumentException("Data length must be 6 bytes")
    }

    val stationNumber = (data[0].toUInt() and 0xFFu shl 8) or (data[1].toUInt() and 0xFFu)

    val cardSerie = CardKind.fromCode(data[2].toUInt() and 0xFFu)
    val cardNumber = ((data[5].toUInt() and 0xFFu)) or
            ((data[4].toUInt() and 0xFFu) shl 8) or
            ((data[3].toUInt() and 0xFFu) shl 16)

    return Triple(cardSerie, stationNumber, cardNumber.toULong())
}

fun toSiRecCommand(frame: SiDataFrame): SiRecCommand {
    return when (frame.command) {
        SiCmd.CARD_REMOVED.code -> {
            val (cardSerie, stationNumber, cardNumber) = parseDataLayoutCardDetectedRemoved(frame.data)
            SiCardRemoved(cardSerie, stationNumber, cardNumber)
        }
        SiCmd.CARD_DETECTED_5.code, SiCmd.CARD_DETECTED_8.code -> {
            val (cardSerie, stationNumber, cardNumber) = parseDataLayoutCardDetectedRemoved(frame.data)
            SiCardDetected(cardSerie, stationNumber, cardNumber)
        }
        SiCmd.GET_CARD_5.code -> {
            // STX, 0xB1, 0x82,
            // CN1, CN0,
            // 128 byte,
            // CRC1, CRC0, ETX
            if (frame.data.size != 128 + 2) {
                throw IllegalArgumentException("Data length must be 130 bytes")
            }
            val stationNumber = getUInt16(frame.data, 0).toInt()
            val data = frame.data.sliceArray(2..129)
            assert(data.size == 128)
            GetSiCardResp(stationNumber, 0, data)
        }
        SiCmd.GET_CARD_8.code -> {
            // STX, 0xEF, 0x83,
            // CN1, CN0,
            // BN
            // 128 byte,
            // CRC1, CRC0, ETX
            if (frame.data.size != 128 + 3) {
                throw IllegalArgumentException("Data length must be 131 bytes")
            }
            val stationNumber = getUInt16(frame.data, 0).toInt()
            val blockNumber = getUByte(frame.data, 2).toInt()
            val data = frame.data.sliceArray(3..130)
            assert(data.size == 128)
            GetSiCardResp(stationNumber, blockNumber, data)
        }
        SiCmd.SIAC_MEASURE_BATTERY.code -> {
            SiacMeasureBatteryVoltageResp()
        }
        else -> throw IllegalArgumentException("Unknown command 0x${frame.command.toString(16)}")
    }
}

sealed class SiSendCommand(val command: SiCmd) {
    abstract fun toSiFrame(): SiDataFrame

    override fun toString(): String {
        return toSiFrame().toString()
    }
}

open class SiacMeasureBatteyVoltage(
) : SiSendCommand(SiCmd.SIAC_MEASURE_BATTERY) {
    override fun toSiFrame(): SiDataFrame {
        // 02 EA 05 7E 05 05 05 05 B2 31 03 - EA - PROBABLY SIAC battery measurement request
        return SiDataFrame(command.code, bytesFromHex("7E 05 05 05 05"))
    }
}

open class GetSiCardBlock(
    cmd: SiCmd,
    val blockNumber: Byte,
) : SiSendCommand(cmd) {
    override fun toSiFrame(): SiDataFrame {
        return SiDataFrame(command.code, byteArrayOf(blockNumber))
    }
}

// STX, 0xB1, 0x00, CRC1, CRC0, ETX
class GetSiCard5Rq() : GetSiCardBlock(SiCmd.GET_CARD_5, 0)

// STX, 0xE1, 0x01, BN, CRC1, CRC0, ETX
class GetSiCard6Rq(
    blockNumber: Byte
) : GetSiCardBlock(SiCmd.GET_CARD_6, blockNumber)

// STX, 0xEF, 0x01, BN, CRC1, CRC0, ETX
class GetSiCard89pRq(
    blockNumber: Byte
) : GetSiCardBlock(SiCmd.GET_CARD_8, blockNumber)
