package org.qxqx.qxdroid

import org.junit.Assert.assertEquals
import org.junit.Test

class UtilsTest {
    @Test
    fun testSha1() {
        val input = "test"
        val expected = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"
        assertEquals(expected, sha1(input))
    }
    @Test
    fun testSha12() {
        val input = "1404741806a94a8fe5ccb19ba61c4c0873d391e987982fbbd3"
        val expected = "519b5e8e489799d6243142ab53ad95e7e90ef160"
        assertEquals(expected, sha1(input))
    }

    @Test
    fun testTimeToString() {
        assertEquals("--:--:--", timeToString(NO_TIME))
        assertEquals("00:00:00", timeToString(0))
        assertEquals("11:36:54", timeToString(11 * 3600 + 36 * 60 + 54))
        assertEquals("00:01:55", timeToString(115))
    }
}
