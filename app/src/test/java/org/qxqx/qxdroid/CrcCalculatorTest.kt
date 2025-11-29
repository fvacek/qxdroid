package org.qxqx.qxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class CrcCalculatorTest {

    @Test
    fun `crc processes last chunk as zero for 4-byte array`() {
        val buffer = bytesFromHex("53 00 05 01 0F B5 00 00 1E 08")
        val result = CrcCalculator.crc(buffer)
        val expected = 0x2C12
        assertEquals(expected, result)
    }

}