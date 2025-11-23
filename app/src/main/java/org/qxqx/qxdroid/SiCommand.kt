package org.qxqx.qxdroid

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

sealed class SiCommand

data class SiCardDetected(
    val cardSerie: CardSerie,
    val stationNumber: UInt,
    val cardNumber: ULong,
) : SiCommand() {
    override fun toString(): String {
        return "Detected: $cardSerie, $cardNumber sn: $stationNumber"
    }
}

data class SiCardRemoved(
    val cardSerie: CardSerie,
    val stationNumber: UInt,
    val cardNumber: ULong,
) : SiCommand() {
    override fun toString(): String {
        return "Removed: $cardSerie, $cardNumber sn: $stationNumber"
    }
}

private const val TAG = "SiCommand"

private fun parseDataLayoutCardDetectedRemoved(data: ByteArray): Triple<CardSerie, UInt, ULong> {
    // The data layout is assumed based on common SPORTident protocol formats,
    // card detected/removed
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

fun toSiCommand(frame: DataFrame): SiCommand {
    if (!frame.ok) {
        throw IllegalArgumentException("Frame is corrupted")
    }
    return when (frame.command) {
        0xE7 -> {
            val (cardSerie, stationNumber, cardNumber) = parseDataLayoutCardDetectedRemoved(frame.data)
            SiCardRemoved(cardSerie, stationNumber, cardNumber)
        }
        0xE5, 0xE8 -> {
            val (cardSerie, stationNumber, cardNumber) = parseDataLayoutCardDetectedRemoved(frame.data)
            SiCardDetected(cardSerie, stationNumber, cardNumber)
        }
        else -> throw IllegalArgumentException("Unknown command 0x${frame.command.toString(16)}")
    }
}
