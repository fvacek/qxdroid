package org.qxqx.qxdroid

import org.junit.Assert.*
import org.junit.Test

class TimeToStringTest {
    @Test
    fun `formats time correctly`() {
        assertEquals("00:00:00", timeToString(0u))
        assertEquals("00:00:01", timeToString(1u))
        assertEquals("00:01:00", timeToString(60u))
        assertEquals("01:00:00", timeToString(3600u))
        assertEquals("12:34:56", timeToString(45296u)) // 12*3600 + 34*60 + 56
    }

    @Test
    fun `handles time rollover`() {
        val oneDayInSeconds = 24u * 3600u
        assertEquals("00:00:00", timeToString(oneDayInSeconds))
        assertEquals("00:00:01", timeToString(oneDayInSeconds + 1u))
        assertEquals("01:02:03", timeToString(oneDayInSeconds + 3600u + 120u + 3u))
    }

    @Test
    fun `handles no time`() {
        val notime = 61166u
        assertEquals("--:--:--", timeToString(notime))
    }
}