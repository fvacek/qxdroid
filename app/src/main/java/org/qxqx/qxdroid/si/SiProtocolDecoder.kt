package org.qxqx.qxdroid.si

import timber.log.Timber
import java.time.LocalDate

class SiProtocolDecoder(
    var sendSiFrame: (SiDataFrame) -> Unit,
    val onCardRead: (SiCard) -> Unit,
) {
    private var currentCard: SiCard? = null
    private var detectedCardKind: CardKind = CardKind.CARD_8
    private var punchesReadCount: Int = 0

    fun onDataFrame(frame: SiDataFrame) {
        try {
            Timber.d("onDataFrame: $frame")
            val sicmd = toSiRecCommand(frame)
            when (sicmd) {
                is SiCardDetected -> {
                    Timber.d("Card detected: $sicmd")
                    when (sicmd.cardKind) {
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
                            Timber.d("TCard detected")
                        }

                        CardKind.SIAC -> {
                            detectedCardKind = CardKind.SIAC
                            sendSiFrame(GetSiCard89pRq(0).toSiFrame())
                        }

                        CardKind.PCARD -> {
                            Timber.d("pCard detected")
                        }
                    }
                }

                is SiCardRemoved -> {
                    Timber.d("Card removed: $sicmd")
                }
                is SiacMeasureBatteryVoltageResp -> {
                    Timber.d("SIAC battery voltage read: $sicmd")
                    sendSiFrame(GetSiCard89pRq(3).toSiFrame())
                }

                is GetSiCardResp -> {
                    Timber.d("Card $detectedCardKind read, block number: ${sicmd.blockNumber}")
                    when (detectedCardKind) {
                        CardKind.CARD_5 -> {
                            Timber.d("Card5 read: $sicmd")
                            parseCard5Data(sicmd.data)
                            onCardRead(currentCard!!)
                        }
                        CardKind.CARD_8 -> {
                            parseCard8Data(sicmd.blockNumber, sicmd.data)
                            if (sicmd.blockNumber == 0) {
                                sendSiFrame(GetSiCard89pRq(1).toSiFrame())
                            } else {
                                assert(currentCard != null)
                                assert(sicmd.blockNumber == 1)
                                onCardRead(currentCard!!)
                            }
                        }
                        CardKind.CARD_9 -> {
                            parseCard9Data(sicmd.blockNumber, sicmd.data)
                            if (sicmd.blockNumber == 0) {
                                sendSiFrame(GetSiCard89pRq(1).toSiFrame())
                            } else {
                                assert(currentCard != null)
                                assert(sicmd.blockNumber == 1)
                                onCardRead(currentCard!!)
                            }
                        }
                        CardKind.SIAC -> {
                            parseSiacData(sicmd.blockNumber, sicmd.data)
                            assert(currentCard != null)
                            if (sicmd.blockNumber == 0) {
                                sendSiFrame(SiacMeasureBatteyVoltage().toSiFrame())
                            } else if (currentCard!!.punches.size == punchesReadCount) {
                                onCardRead(currentCard!!)
                            } else {
                                sendSiFrame(GetSiCard89pRq((sicmd.blockNumber + 1).toByte()).toSiFrame())
                            }
                        }
                        else -> {}
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
        }
        currentCard = SiCard(
            CardKind.CARD_5,
            cardSerie,
            cardNumber,
            checkTime,
            startTime,
            finishTime,
            punches.toTypedArray()
        )
        punchesReadCount = punchCount
    }
    private fun parseCard8Data(blockNumber: Int, data: ByteArray) {
        if (blockNumber == 0) {
            currentCard = parseCard89FirstBlockData(CardKind.CARD_8, data)
            punchesReadCount = 0
        }
        assert(blockNumber == 1)
        assert(currentCard != null)
        val card = currentCard!!

        for (i in 0 until (card.punches.size)) {
            val offset = 2 * 4 + i * 4
            card.punches[i].code = getUByte(data, offset + 1).toInt()
            card.punches[i].time = getUInt16(data, offset + 2).toInt()
        }
        punchesReadCount = card.punches.size
    }

    private fun parseCard9Data(blockNumber: Int, data: ByteArray) {
        if (blockNumber == 0) {
            val card = parseCard89FirstBlockData(CardKind.CARD_9, data)
            currentCard = card
            punchesReadCount = minOf(card.punches.size, 18)
            for (i in 0 until punchesReadCount) {
                val offset = 14 * 4 + i * 4
                card.punches[i].code = getUByte(data, offset + 1).toInt()
                card.punches[i].time = getUInt16(data, offset + 2).toInt()
            }
            return
        }
        assert(blockNumber == 1)
        assert(currentCard != null)
        val card = currentCard!!
        for (i in punchesReadCount until (card.punches.size)) {
            val offset = 2 * 4 + i * 4
            card.punches[i].code = getUByte(data, offset + 1).toInt()
            card.punches[i].time = getUInt16(data, offset + 2).toInt()
        }
    }

    private fun parseSiacData(blockNumber: Int, data: ByteArray) {
        if (blockNumber == 0) {
            currentCard = parseCard89FirstBlockData(CardKind.SIAC, data)
            punchesReadCount = 0
            return
        }
        assert(currentCard != null)
        val card = currentCard!!
        if (blockNumber == 3) {
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

            return
        }

        if (blockNumber > 3) {
            // do not read more than 32 punch records from one page
            val maxCount = minOf(card.punches.size, punchesReadCount + 32)
            var offset = 0
            for (i in punchesReadCount until maxCount) {
                card.punches[i].code = getUByte(data, offset + 1).toInt()
                card.punches[i].time = getUInt16(data, offset + 2).toInt()
                offset += 4
            }
            punchesReadCount = maxCount
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
        )
    )
}
