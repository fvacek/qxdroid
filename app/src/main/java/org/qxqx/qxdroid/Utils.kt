package org.qxqx.qxdroid

// This function is now available anywhere in the project
fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it).uppercase() }
}

fun bytesFromHex(hexString: String): ByteArray {
    val hexstr = hexString.replace(Regex("\\s"), "")
    require(hexstr.length % 2 == 0) { "Hex string must have an even length" }
    return hexstr.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

private const val NO_TIME = 61166
fun timeToString(time: Int): String {
    if (time == NO_TIME) {
        return "--:--:--"
    }
    val sec = time % 60
    val min = (time / 60) % 60
    val hour = (time / 3600) % 24
    return "%02d:%02d:%02d".format(hour, min, sec)
}

