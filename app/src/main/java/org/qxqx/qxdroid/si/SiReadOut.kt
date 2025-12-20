package org.qxqx.qxdroid.si

import org.qxqx.qxdroid.shv.RpcValue

sealed class SiReadOut {
    data class Card(val card: SiCard) : SiReadOut() {
        override fun toRpcValue(): RpcValue {
            return card.toRpcValue()
        }
    }

    data class Punch(val punch: SiPunch) : SiReadOut() {
        override fun toRpcValue(): RpcValue {
            return punch.toRpcValue()
        }
    }

    data class CardDetected(val command: SiCardDetected) : SiReadOut() {
        override fun toRpcValue(): RpcValue {
            return RpcValue.Map(
                mapOf(
                    "cardNumber" to RpcValue.Int(command.cardNumber.toLong()),
                    "stationNumber" to RpcValue.Int(command.stationNumber.toLong()),
                )
            )
        }
    }
    data class CardRemoved(val command: SiCardRemoved) : SiReadOut() {
        override fun toRpcValue(): RpcValue {
            return RpcValue.Map(
                mapOf(
                    "cardNumber" to RpcValue.Int(command.cardNumber.toLong()),
                    "stationNumber" to RpcValue.Int(command.stationNumber.toLong()),
                )
            )
        }
    }

    abstract fun toRpcValue(): RpcValue
}