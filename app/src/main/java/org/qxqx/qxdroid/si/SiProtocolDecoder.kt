package org.qxqx.qxdroid.si

import timber.log.Timber
import java.time.LocalDate
import kotlin.math.min

class SiProtocolDecoder(
    var sendSiFrame: (SiDataFrame) -> Unit,
    val onCardRead: (SiCard) -> Unit,
) {
    private var currentCard: SiCard? = null
    private var detectedCardKind: CardKind = CardKind.CARD_8
    private var punchesReadCount: Int = 0

    fun onCardReadPrivate(card: SiCard) {
        sendSiFrame(StationBeepRq().toSiFrame())
        onCardRead(card)
    }
    fun onDataFrame(frame: SiDataFrame) {
        try {
            Timber.d("onDataFrame: $frame")
            val sicmd = toSiRecCommand(frame)
            when (sicmd) {
                is SiCardDetected -> {
                    Timber.d("Card detected: $sicmd")
                    when (sicmd.command) {
                        SiCmd.CARD_DETECTED_5 -> {
                            detectedCardKind = CardKind.CARD_5
                            sendSiFrame(GetSiCard5Rq().toSiFrame())
                        }

                        SiCmd.CARD_DETECTED_6 -> {
                            val cmd = GetSiCard6Rq(0)
                            sendSiFrame(cmd.toSiFrame())
                        }

                        SiCmd.CARD_DETECTED_89pt -> {
                            sendSiFrame(GetSiCard89ptRq(0).toSiFrame())
                        }

                        else -> {
                            Timber.w("Unexpected card detected command: $sicmd")
                        }
                    }
                }

                is SiCardRemoved -> {
                    Timber.d("Card removed: $sicmd")
                }
                is SiacMeasureBatteryVoltageResp -> {
                    Timber.d("SIAC battery voltage read: $sicmd")
                    sendSiFrame(GetSiCard89ptRq(3).toSiFrame())
                }

                is GetSiCardResp -> {
                    Timber.d("Card $detectedCardKind read, block number: ${sicmd.blockNumber}")
                    if (sicmd.blockNumber > 7) {
                        // do not read forever in case of internal bug
                        Timber.w("Bloc number too high, internal read card error")
                        return
                    }
                    when (sicmd.command) {
                        SiCmd.GET_CARD_5 -> {
                            Timber.d("Card5 read: $sicmd")
                            parseCard5Data(sicmd.data)
                            onCardReadPrivate(currentCard!!)
                        }
                        SiCmd.GET_CARD_6 -> {
                            parseCard6Data(sicmd.blockNumber, sicmd.data)
                            val card = currentCard!!
                            if (punchesReadCount == card.punches.size) {
                                onCardReadPrivate(card)
                            } else if (sicmd.blockNumber == 0) {
                                sendSiFrame(GetSiCard6Rq(6).toSiFrame())
                            } else if (sicmd.blockNumber == 7) {
                                sendSiFrame(GetSiCard6Rq(2).toSiFrame())
                            } else {
                                sendSiFrame(GetSiCard89ptRq(sicmd.blockNumber + 1).toSiFrame())
                            }
                        }
                        SiCmd.GET_CARD_89pt -> {
                            parseCard89ptData(sicmd.blockNumber, sicmd.data)
                            val card = currentCard!!
                            if (punchesReadCount == card.punches.size) {
                                onCardReadPrivate(card)
                            } else if (sicmd.blockNumber == 0) {
                                when (card.cardKind) {
                                    CardKind.SIAC -> {
                                        sendSiFrame(SiacMeasureBatteyVoltage().toSiFrame())
                                    }
                                    else -> {
                                        sendSiFrame(GetSiCard89ptRq(1).toSiFrame())
                                    }
                                }
                            } else {
                                sendSiFrame(GetSiCard89ptRq(sicmd.blockNumber + 1).toSiFrame())
                            }
                        }
                        else -> {
                            Timber.w("GetSiCardResp unexpected command: $sicmd")
                        }
                    }
                }
                else -> {
                    Timber.d("Unknown command: $sicmd")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "SI frame corrupted error")
        }
    }
    private fun parseCard5Data(data: ByteArray) {
        val byte: (Int) -> UByte = { offset -> data[offset].toUByte() }
        val cardSerie = byte(0x06).toInt()
        var cardNumber = getUInt16(data,0x04).toLong()
        if(cardSerie > 1) {
            cardNumber += 100000 * cardSerie
        }
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
        }
        currentCard = SiCard(
            CardKind.CARD_5,
            cardNumber,
            checkTime,
            startTime,
            finishTime,
            punches.toTypedArray()
        )
        punchesReadCount = punchCount
    }
    private fun parseCard6Data(blockNumber: Int, data: ByteArray) {
        if (blockNumber == 0) {
            val cardKind = CardKind.fromCode(getUByte(data, 2 * 4 + 0).toUInt())
            assert(cardKind == CardKind.CARD_6)
            val cardNumber = getUInt24(data, 2 * 4 + 3).toLong()
            val punchCount = getUByte(data, 4 * 4 + 2).toInt()
            val finishTime = getUInt16(data, 5 * 4 + 2).toInt()
            val startTime = getUInt16(data, 6 * 4 + 2).toInt()
            val checkTime = getUInt16(data, 7 * 4 + 2).toInt()

            currentCard = SiCard(
                cardKind!!, cardNumber, checkTime, startTime, finishTime, Array<SiPunch>(
                    punchCount,
                    init = { SiPunch(0, 0) }
                )
            )
            punchesReadCount = 0
            return
        }
        assert(currentCard != null)
        val card = currentCard!!

        var n = 0
        for (i in punchesReadCount until (card.punches.size)) {
            val offset = n * 4
            card.punches[punchesReadCount + i].code = getUByte(data, offset + 1).toInt()
            card.punches[punchesReadCount + i].time = getUInt16(data, offset + 2).toInt()
            n += 1
        }
        punchesReadCount += n
    }

    private fun parseCard89ptData(blockNumber: Int, data: ByteArray) {
        if (blockNumber == 0) {
            val card = parseCard89ptFirstBlockData(data)
            currentCard = card
            punchesReadCount = 0
            if (card.cardKind == CardKind.CARD_9) {
                parseCardPunchingData(14, data)
            } else if (card.cardKind == CardKind.TCARD) {
                parseTCardPunchingData(7, data)
            }
            return
        }
        assert(currentCard != null)
        val card = currentCard!!
        if (card.cardKind == CardKind.CARD_8 && blockNumber == 1) {
            parseCardPunchingData(2, data)
            return
        }
        if (card.cardKind == CardKind.SIAC && blockNumber == 3) {
            parseSacBatteryStatus(data)
            return
        }
        if (card.cardKind == CardKind.PCARD && blockNumber == 1) {
            parseCardPunchingData(12, data)
            return
        }
        if (card.cardKind == CardKind.TCARD) {
            parseTCardPunchingData(0, data)
            return
        }
        parseCardPunchingData(0, data)
    }

    private fun parseCardPunchingData(recordOffset: Int, data: ByteArray) {
        assert(currentCard != null)
        val card = currentCard!!

        val maxCount = min(128 / 4 - recordOffset, card.punches.size - punchesReadCount)
        for (i in 0 until maxCount) {
            val offset = (recordOffset + i) * 4
            card.punches[punchesReadCount + i].code = getUByte(data, offset + 1).toInt()
            card.punches[punchesReadCount + i].time = getUInt16(data, offset + 2).toInt()
        }
        punchesReadCount += maxCount
    }

    private fun parseTCardPunchingData(recordOffset: Int, data: ByteArray) {
        assert(currentCard != null)
        val card = currentCard!!

        val maxCount = min(128 / 8 - recordOffset, card.punches.size - punchesReadCount)
        for (i in 0 until maxCount) {
            val offset = (recordOffset + i) * 8
            card.punches[i].code = getUByte(data, offset + 0).toInt()
            card.punches[i].time = getUInt16(data, offset + 5).toInt()
        }
        punchesReadCount += maxCount
    }

    private fun parseSacBatteryStatus(data: ByteArray) {
        assert(currentCard != null)
        val card = currentCard!!
        // read battery status
        var offset = 0x0F * 4
        val yy = getUByte(data, offset + 0).toInt() + 2000
        val mm = getUByte(data, offset + 1).toInt()
        val dd = getUByte(data, offset + 2).toInt()
        val newBatteryDate = LocalDate.of(yy, mm, dd)
        Timber.d("SIAC new batery date: $newBatteryDate")

        offset = 0x11 * 4
        val mvbat = getUByte(data, offset + 3).toInt()
        // Real battery voltage calculation: 1.9 + (BATT_VOLTAGE * 0.09) /* 1.9V is offset and 0.09 V LSB */
        val batteryVoltage = 1.9 + (mvbat * 0.09)

        offset = 0x15 * 4
        val rbat = getUByte(data, offset + 0).toInt()
        // RBAT	reference voltage
        val batteryReferenceVoltage = 1.9 + (rbat * 0.09)

        val lbat = getUByte(data, offset + 1).toInt()
        // LBAT	low battery indicator:  0xAA - ok, 0x6C – low bat
        val batteryLow = lbat != 0xAA

        Timber.d("SIAC battery voltage: $batteryVoltage, reference: $batteryReferenceVoltage, low: $batteryLow")

        card.baterry = SiacBatteryStatus(batteryVoltage, batteryLow, newBatteryDate)
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
private fun parseCard89ptFirstBlockData(data: ByteArray): SiCard {
    val checkTime = getUInt16(data, 2 * 4 + 2).toInt()
    val startTime = getUInt16(data, 3 * 4 + 2).toInt()
    val finishTime = getUInt16(data, 4 * 4 + 2).toInt()
    val cardNumber = getUInt24(data, 6 * 4 + 1).toLong()
    val punchCount = getUByte(data, 5 * 4 + 2).toInt()
    val cardCode = getUByte(data, 6 * 4 + 0).toUInt()
    val cardKindFromCode = CardKind.fromCode(cardCode)
    val cardKind = if (cardKindFromCode == null || cardCode == CardKind.SIAC.code) {
        // card 10, 11 and Siac have same code 15
        CardKind.fromCardNumber(cardNumber)
    } else {
        cardKindFromCode
    }

    return SiCard(
        cardKind, cardNumber, checkTime, startTime, finishTime, Array<SiPunch>(
            punchCount,
            init = { SiPunch(0, 0) }
        )
    )
}
