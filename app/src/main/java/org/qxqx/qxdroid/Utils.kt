package org.qxqx.qxdroid

// This function is now available anywhere in the project
fun bytesToHex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it).uppercase() }
}
