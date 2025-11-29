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
