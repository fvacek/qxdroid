package org.qxqx.qxdroid.si

import android.util.Log

private const val TAG = "SiReader"

class SiReaderWriter(
    var sendSiFrame: (SiDataFrame) -> Unit,
    val onCardRead: (SiCard) -> Unit,
) {
    private var currentCard: SiCard? = null
    private var detectedCardKind: CardKind = CardKind.CARD_8

    fun onDataFrame(frame: SiDataFrame) {
        try {
            Log.d(TAG, "onDataFrame: $frame")
            val sicmd = toSiRecCommand(frame)
            when (sicmd) {
                is SiCardDetected -> {
                    Log.d(TAG, "Card detected: $sicmd")
                    when (sicmd.cardSerie) {
                        CardKind.CARD_5 -> {
                            detectedCardKind = CardKind.CARD_5
                            sendSiFrame(GetSiCard5Rq().toSiFrame())
                        }

                        CardKind.CARD_8 -> {
                            detectedCardKind = CardKind.CARD_8
                            val cmd = GetSiCard89pRq(0)
                            sendSiFrame(cmd.toSiFrame())
                        }

                        CardKind.CARD_9 -> {
                            detectedCardKind = CardKind.CARD_9
                            sendSiFrame(GetSiCard89pRq(0).toSiFrame())
                        }

                        CardKind.TCARD -> {
                            Log.d(TAG, "TCard detected")
                        }

                        CardKind.SIAC -> {
                            Log.d(TAG, "SIAC detected")
                        }

                        CardKind.PCARD -> {
                            Log.d(TAG, "pCard detected")
                        }
                    }
                }

                is SiCardRemoved -> {
                    Log.d(TAG, "Card detected: $sicmd")
                }

                is GetSiCardResp -> {
                    Log.d(TAG, "Card $detectedCardKind read, block number: ${sicmd.blockNumber}")
                    when (detectedCardKind) {
                        CardKind.CARD_5 -> {
                            Log.d(TAG, "Card5 read: $sicmd")
                            val card = parseCard5Data(sicmd.data)
                            onCardRead(card)
                        }
                        CardKind.CARD_8 -> {
                            currentCard = parseCard8Data(currentCard, sicmd.blockNumber, sicmd.data)
                            if (sicmd.blockNumber == 0) {
                                sendSiFrame(GetSiCard89pRq(1).toSiFrame())
                            } else {
                                assert(currentCard != null)
                                onCardRead(currentCard!!)
                            }
                        }
                        CardKind.CARD_9 -> {
                            currentCard = parseCard9Data(currentCard, sicmd.blockNumber, sicmd.data)
                            if (sicmd.blockNumber == 0) {
                                sendSiFrame(GetSiCard89pRq(1).toSiFrame())
                            } else {
                                assert(currentCard != null)
                                onCardRead(currentCard!!)
                            }
                        }
                        else -> {}
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown command: $sicmd")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "SI frame corrupted error: $e")
        }
    }
}

fun getUByte(data: ByteArray, offset: Int): UByte {
    return data[offset].toUByte()
}
fun getUInt16(data: ByteArray, offset: Int): UInt {
    val hi = data[offset].toUByte()
    val lo = data[offset + 1].toUByte()
    val ret = hi * 256u + lo
    return ret
}
fun getUInt24(data: ByteArray, offset: Int): UInt {
    val hi = data[offset].toUByte()
    val mi = data[offset + 1].toUByte()
    val lo = data[offset + 2].toUByte()
    val ret = hi * 256u * 256u + mi * 256u + lo
    return ret
}
fun parseCard5Data(data: ByteArray): SiCard {
    val byte: (Int) -> UByte = { offset -> data[offset].toUByte() }
    val cardSerie = byte(0x06).toInt()
    val cardNumber = getUInt16(data,0x04).toInt()
    val startTime = getUInt16(data,0x13).toInt()
    val finishTime = getUInt16(data,0x15).toInt()
    val punchCount = byte(0x17).toInt() - 1
    val checkTime = getUInt16(data,0x19).toInt()
    val punches = mutableListOf<SiPunch>()
    for (i in 0 until punchCount) {
        val offset = 0x20 + i / 5 * 16 + 1 + 3 * (i % 5)
        val code = byte(offset)
        val punchTime = getUInt16(data, offset + 1).toInt()
        val punch = SiPunch(code.toInt(), punchTime)
        punches.add(punch)
        //Log.d(TAG, "Punch: $punch")
    }
    return SiCard(
        CardKind.CARD_5,
        cardSerie,
        cardNumber,
        checkTime,
        startTime,
        finishTime,
        punches.toTypedArray()
    )
}

private fun parseCard89FirstBlockData(cardKind: CardKind, data: ByteArray): SiCard {
    val checkTime = getUInt16(data, 2 * 4 + 2).toInt()
    val startTime = getUInt16(data, 3 * 4 + 2).toInt()
    val finishTime = getUInt16(data, 4 * 4 + 2).toInt()
    val cardNumber = getUInt24(data, 6 * 4 + 1).toInt()
    val cardSerie = (getUByte(data, 6 * 4 + 0) and 0x0Fu).toInt()
    val punchCount = getUByte(data, 5 * 4 + 2).toInt()

    return SiCard(
        cardKind, cardSerie, cardNumber, checkTime, startTime, finishTime, Array<SiPunch>(
            punchCount,
            init = { SiPunch(0, 0) }
        ))
}

private fun parseCard8Data(currentCard: SiCard?, blockNumber: Int, data: ByteArray): SiCard {
    if (blockNumber == 0) {
        return parseCard89FirstBlockData(CardKind.CARD_8, data)
    }
    assert(blockNumber == 1)
    assert(currentCard != null)

    for (i in 0 until (currentCard!!.punches.size)) {
        val offset = 2 * 4 + i * 4
        currentCard.punches[i].code = getUByte(data, offset + 1).toInt()
        currentCard.punches[i].time = getUInt16(data, offset + 2).toInt()
    }
    return currentCard
}

fun parseCard9Data(currentCard: SiCard?, blockNumber: Int, data: ByteArray): SiCard {
    //Log.d(TAG, "parseCard9Data, blockNumber: $blockNumber, current card: $currentCard")
    if (blockNumber == 0) {
        var card = parseCard89FirstBlockData(CardKind.CARD_9, data)
        for (i in 0 until minOf(card.punches.size, 18)) {
            val offset = 14 * 4 + i * 4
            card.punches[i].code = getUByte(data, offset + 1).toInt()
            card.punches[i].time = getUInt16(data, offset + 2).toInt()
        }
        return card
    }
    assert(blockNumber == 1)
    assert(currentCard != null)

    for (i in 18 until (currentCard!!.punches.size)) {
        val offset = 2 * 4 + i * 4
        currentCard.punches[i].code = getUByte(data, offset + 1).toInt()
        currentCard.punches[i].time = getUInt16(data, offset + 2).toInt()
    }
    return currentCard
}

