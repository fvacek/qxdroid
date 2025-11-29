package org.qxqx.qxdroid

private const val TAG = "SiCommand"

enum class SiCmd(val code: Int) {
    INVALID(0),
    GET_CARD_5(0xB1),
    GET_CARD_6(0xE1),
    GET_CARD_8(0xEF),
    CARD_DETECTED_5(0xE5),
    CARD_REMOVED(0xE7),
    CARD_DETECTED_8(0xE8);

    companion object {
        fun fromCode(code: Int): SiCmd =
            entries.firstOrNull { it.code == code } ?: INVALID
    }
}

enum class CardSerie(val code: UInt) {
    CARD_5(0u),
    CARD_8(2u),
    CARD_9(1u),
    PCARD(4u),
    TCARD(6u),
    SIAC(15u);

    companion object {
        fun fromCode(code: UInt): CardSerie =
            entries.firstOrNull { it.code == code } ?: CARD_5
    }
}

sealed class SiRecCommand()

data class SiCardDetected(
    val cardSerie: CardSerie,
    val stationNumber: UInt,
    val cardNumber: ULong,
) : SiRecCommand() {
    override fun toString(): String {
        return "Detected: $cardSerie, $cardNumber sn: $stationNumber"
    }
}

data class SiCardRemoved(
    val cardSerie: CardSerie,
    val stationNumber: UInt,
    val cardNumber: ULong,
) : SiRecCommand() {
    override fun toString(): String {
        return "Removed: $cardSerie, $cardNumber sn: $stationNumber"
    }
}

data class SiPunch(
    val code: UInt,
    val time: UInt,
)

data class SiCard(
    val cardSerie: UByte,
    val cardNumber: ULong,
    val checkTime: UInt,
    val startTime: UInt,
    val finishTime: UInt,
    val punches: Array<SiPunch>,
) : SiRecCommand() {
}

data class GetSiCard5Resp(
    val stationNumber: UInt,
    val data: ByteArray
) : SiRecCommand() {
}

private fun parseDataLayoutCardDetectedRemoved(data: ByteArray): Triple<CardSerie, UInt, ULong> {
    // Assumed layout: [stationNumber: 2 bytes BE, cardNumber: 4 bytes BE]
    if (data.size != 6) {
        throw IllegalArgumentException("Data length must be 6 bytes")
    }

    val stationNumber = (data[0].toUInt() and 0xFFu shl 8) or (data[1].toUInt() and 0xFFu)

    val cardSerie = CardSerie.fromCode(data[2].toUInt() and 0xFFu)
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
            //STX, 0xB1,
            // 0x82, CN1, CN0, 128 byte,
            // CRC1, CRC0, ETX
            if (frame.data.size != 128 + 3) {
                throw IllegalArgumentException("Data length must be 128 + 3 bytes")
            }
            if ((frame.data[0].toUInt() and 0xFFu) != 0x82u) {
                throw IllegalArgumentException("Data[0] must be 0x82")
            }
            val stationNumber = (frame.data[1].toUInt() and 0xFFu shl 8) or (frame.data[2].toUInt() and 0xFFu)
            val data = frame.data.sliceArray(3..130)
            GetSiCard5Resp(stationNumber, data)
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

open class GetSiCardBlock(
    cmd: SiCmd,
    val blockNumber: Byte,
) : SiSendCommand(cmd) {
    override fun toSiFrame(): SiDataFrame {
        return SiDataFrame(command.code, byteArrayOf(0x01, blockNumber))
    }
}

// STX, 0xB1, 0x00, CRC1, CRC0, ETX
class GetSiCard5Rq() : SiSendCommand(SiCmd.GET_CARD_5) {
    override fun toSiFrame(): SiDataFrame {
        return SiDataFrame(command.code, byteArrayOf(0x00))
    }
}

// STX, 0xE1, 0x01, BN, CRC1, CRC0, ETX
class GetSiCard6Rq(
    blockNumber: Byte
) : GetSiCardBlock(SiCmd.GET_CARD_6, blockNumber)

// STX, 0xEF, 0x01, BN, CRC1, CRC0, ETX
class GetSiCard8Rq(
    blockNumber: Byte
) : GetSiCardBlock(SiCmd.GET_CARD_8, blockNumber)
