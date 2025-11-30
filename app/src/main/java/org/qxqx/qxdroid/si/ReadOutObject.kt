package org.qxqx.qxdroid.si

sealed class ReadOutObject {
    data class CardReadObject(val card: SiCard) : ReadOutObject()
    data class Command(val command: SiRecCommand) : ReadOutObject()
}