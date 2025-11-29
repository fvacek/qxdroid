package org.qxqx.qxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class CrcCalculatorTest {

    @Test
    fun `crc test`() {
        for ((data, expected) in listOf(
            "53 00 05 01 0F B5 00 00 1E 08" to 0x2C12,
            "E5060004000010E9" to 0x378C,
            )
        ) {
            val buffer = bytesFromHex(data)
            val result = CrcCalculator.crc(buffer)
            assertEquals(expected, result)
        }
    }

}