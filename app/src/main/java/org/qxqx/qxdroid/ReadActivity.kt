package org.qxqx.qxdroid

sealed class ReadActivity {
    data class CardRead(val card: SiCard) : ReadActivity()
    data class Command(val command: SiRecCommand) : ReadActivity()
}