package org.qxqx.qxdroid

import android.util.Log

private const val TAG = "SiReader"

class SiReader(
    val sendSiFrame: (SiDataFrame) -> Unit,
    val onCardRead: (SiCard) -> Unit,
) {
    fun onDataFrame(frame: SiDataFrame) {
        try {
            Log.d(TAG, "onDataFrame: $frame")
            val sicmd = toSiRecCommand(frame)
            when (sicmd) {
                is SiCardDetected -> {
                    Log.d(TAG, "Card detected: $sicmd")
                    when (sicmd.cardSerie) {
                        CardSerie.CARD_5 -> {
                            //Log.d(TAG, "Card 5 detected")
                            val cmd = GetSiCard5Rq()
                            sendSiFrame(cmd.toSiFrame())
                        }

                        CardSerie.CARD_8 -> {
                            Log.d(TAG, "Card 8 detected")
                        }

                        CardSerie.CARD_9, CardSerie.PCARD -> {
                            Log.d(TAG, "Card 9 detected")
                        }

                        CardSerie.TCARD -> {
                            Log.d(TAG, "TCard detected")
                        }

                        CardSerie.SIAC -> {
                            Log.d(TAG, "SIAC detected")
                        }
                    }
                }

                is SiCardRemoved -> {
                    Log.d(TAG, "Card detected: $sicmd")
                }

                is GetSiCard5Resp -> {
                    Log.d(TAG, "Card5 read: $sicmd")
                    val card = parseCard5Data(sicmd.data)
                    onCardRead(card)
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
private fun parseCard5Data(data: ByteArray): SiCard {
    val byte: (Int) -> UByte = { offset -> data[offset].toUByte() }
    val cardSerie = byte(0x06)
    val cardNumber = getUInt16(data,0x04)
    val startTime = getUInt16(data,0x13)
    val finishTime = getUInt16(data,0x15)
    val punchCount = byte(0x17).toInt()
    val checkTime = getUInt16(data,0x19)
    val punches = mutableListOf<SiPunch>()
    for (i in 0 until punchCount) {
        val offset = 0x20 + i / 5 * 16 + 1 + 3 * (i % 5)
        val code = byte(offset)
        val punchTime = getUInt16(data, offset + 1)
        val punch = SiPunch(code.toUInt(), punchTime)
        punches.add(punch)
        Log.d(TAG, "Punch: $punch")
    }
    return SiCard(cardSerie, cardNumber.toULong(), checkTime, startTime, finishTime, punches.toTypedArray())
}


