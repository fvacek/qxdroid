package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test

class TimeToStringTest {
    @Test
    fun `formats time correctly`() {
        assertEquals("00:00:00", timeToString(0))
        assertEquals("00:00:01", timeToString(1))
        assertEquals("00:01:00", timeToString(60))
        assertEquals("01:00:00", timeToString(3600))
        assertEquals("12:34:56", timeToString(45296)) // 12*3600 + 34*60 + 56
    }

    @Test
    fun `handles time rollover`() {
        val oneDayInSeconds = 24 * 3600
        assertEquals("00:00:00", timeToString(oneDayInSeconds))
        assertEquals("00:00:01", timeToString(oneDayInSeconds + 1))
        assertEquals("01:02:03", timeToString(oneDayInSeconds + 3600 + 120 + 3))
    }

    @Test
    fun `handles no time`() {
        val notime = 61166
        assertEquals("--:--:--", timeToString(notime))
    }
}