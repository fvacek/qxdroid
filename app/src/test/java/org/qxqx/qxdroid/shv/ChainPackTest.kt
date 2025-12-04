package org.qxqx.qxdroid.shv

import android.R
import org.junit.Test
import org.junit.Assert.*
import org.qxqx.qxdroid.bytesFromHex
import org.qxqx.qxdroid.bytesToHex
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

private fun chainPackToRpcValue(hex_data: String): RpcValue {
    val buff = bytesFromHex(hex_data);
    val input = ByteArrayInputStream(buff)
    val reader = ChainPackReader(input)
    val readValue = reader.read()
    return readValue
}

private fun rpcValueToChainPack(value: RpcValue): String {
    val out = ByteArrayOutputStream()
    val writer = ChainPackWriter(out)
    writer.write(value)
    return bytesToHex(out.toByteArray())
}

private fun testReadWriteTwist(value: RpcValue, hexChainpack: String) {
    assertEquals(hexChainpack, rpcValueToChainPack(value))
    assertEquals(value, chainPackToRpcValue(hexChainpack))
}

class ChainPackTest {
    @Test
    fun testUInt() {
        for (i in 0..65537 step 137) {
            val out = ByteArrayOutputStream()
            val writer = ChainPackWriter(out)
            val testValue = i.toULong()
            writer.write(RpcValue.UInt(testValue))

            val input = ByteArrayInputStream(out.toByteArray())
            val reader = ChainPackReader(input)
            val readValue = reader.read()

            assertTrue(readValue is RpcValue.UInt)
            assertEquals(testValue, (readValue as RpcValue.UInt).value)
        }
    }

    @Test
    fun testInt() {
        for (testValue in -65537..65537 step 137) {
            val out = ByteArrayOutputStream()
            val writer = ChainPackWriter(out)
            val testValue = testValue.toLong()
            writer.write(RpcValue.Int(testValue))

            val input = ByteArrayInputStream(out.toByteArray())
            val reader = ChainPackReader(input)
            val readValue = reader.read()

            assertTrue(readValue is RpcValue.Int)
            assertEquals(testValue, (readValue as RpcValue.Int).value)
        }
    }

    @Test
    fun testBigUInt() {
        for (testValue in listOf(ULong.MAX_VALUE - 1u, ULong.MAX_VALUE)) {
            val out = ByteArrayOutputStream()
            val writer = ChainPackWriter(out)
            writer.write(RpcValue.UInt(testValue))

            val input = ByteArrayInputStream(out.toByteArray())
            val reader = ChainPackReader(input)
            val readValue = reader.read()

            assertTrue(readValue is RpcValue.UInt)
            assertEquals(testValue, (readValue as RpcValue.UInt).value)
        }
    }

    @Test
    fun testBigInt() {
        for (testValue in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            val out = ByteArrayOutputStream()
            val writer = ChainPackWriter(out)
            writer.write(RpcValue.Int(testValue))

            val input = ByteArrayInputStream(out.toByteArray())
            val reader = ChainPackReader(input)
            val readValue = reader.read()

            assertTrue(readValue is RpcValue.Int)
            assertEquals(testValue, (readValue as RpcValue.Int).value)
        }
    }

    @Test
    fun testWriteTinyUInt() {
        for (i in 0..63) {
            val out = ByteArrayOutputStream()
            val writer = ChainPackWriter(out)
            writer.write(RpcValue.UInt(i.toULong()))
            val ba = out.toByteArray()
            assertEquals(1, ba.size)
            assertEquals(i.toUInt(), ba[0].toUInt())
        }
    }
    @Test
    fun testWriteTinyInt() {
        for (i in 0..63) {
            val out = ByteArrayOutputStream()
            val writer = ChainPackWriter(out)
            writer.write(RpcValue.Int(i.toLong()))
            val ba = out.toByteArray()
            assertEquals(1, ba.size)
            assertEquals(i, ba[0].toInt() - 64)
        }
    }

    @Test
    fun testString() {
        testReadWriteTwist(RpcValue.String("AHOJ!"), "860541484F4A21")
    }

    @Test
    fun testBool() {
        testReadWriteTwist(RpcValue.Bool(true), "FE")
        testReadWriteTwist(RpcValue.Bool(false), "FD")
    }

    @Test
    fun testBlob() {
        val blob = ByteArray(10) { 0xAA.toByte() }
        testReadWriteTwist(RpcValue.Blob(blob), "850AAAAAAAAAAAAAAAAAAAAA")
    }

    @Test
    fun testList() {
        val list = RpcValue.List(listOf(
            RpcValue.String("a"),
            RpcValue.Int(123),
            RpcValue.Bool(true),
            RpcValue.List(listOf(RpcValue.Int(1), RpcValue.Int(2), RpcValue.Int(3))),
            RpcValue.Null()
        ))
        testReadWriteTwist(list, "8886016182807BFE88414243FF80FF")
    }

    @Test
    fun testMap() {
        val map = RpcValue.Map(mapOf(
            "bar" to RpcValue.Int(2),
            "baz" to RpcValue.Int(3),
            "foo" to RpcValue.List(listOf(RpcValue.Int(11), RpcValue.Int(12), RpcValue.Int(13)))
        ))
        testReadWriteTwist(map, "89860362617242860362617A438603666F6F884B4C4DFFFF")
    }

    @Test
    fun testIMap() {
        val map = RpcValue.IMap(mapOf(
            1 to RpcValue.String("foo"),
            2 to RpcValue.String("bar"),
            333 to RpcValue.Int(15),
        ))
        testReadWriteTwist(map, "8A418603666F6F42860362617282814D4FFF")
    }

    @Test
    fun testDouble() {
        testReadWriteTwist(RpcValue.Double(-9.094_583_978_896_067E-39), "830000000000C208B8")
    }

    @Test
    fun testDecimal() {
        testReadWriteTwist(RpcValue.Decimal(0, 0), "8C0000")
        testReadWriteTwist(RpcValue.Decimal(321, -2), "8C814142")
    }

    @Test
    fun testDateTime() {
        testReadWriteTwist(RpcValue.DateTime(1517529600001, 0), "8D04");
        testReadWriteTwist(RpcValue.DateTime(1517529600001, 3600), "8D8211");
        testReadWriteTwist(RpcValue.DateTime(1543708800000, 0), "8DE63DDA02");
        testReadWriteTwist(RpcValue.DateTime(1514764800000, 0), "8DE8A8BFFE");
        testReadWriteTwist(RpcValue.DateTime(1546300800000, 0), "8DE6DC0E02");
        testReadWriteTwist(RpcValue.DateTime(1577836800000, 0), "8DF00E60DC02");
        testReadWriteTwist(RpcValue.DateTime(1609459200000, 0), "8DF015EAF002");
        testReadWriteTwist(RpcValue.DateTime(1924992000000, 0), "8DF061258802");
        testReadWriteTwist(RpcValue.DateTime(2240611200000, 0), "8DF100AC656602");
        testReadWriteTwist(RpcValue.DateTime(2246004900000, -36900), "8DF156D74D495F");
        testReadWriteTwist(RpcValue.DateTime(2246004900123, -36900), "8DF301533905E2375D");
        testReadWriteTwist(RpcValue.DateTime(0, 0), "8DF18169CEA7FE");
        testReadWriteTwist(RpcValue.DateTime(1493790723000, 0), "8DEDA8E7F2");
        testReadWriteTwist(RpcValue.DateTime(1493826723923, 0), "8DF1961334BEB4");
        testReadWriteTwist(RpcValue.DateTime(1493790751123, 36000), "8DF28B0DE42CD95F");
        testReadWriteTwist(RpcValue.DateTime(1493826723000, 0), "8DEDA6B572");
        testReadWriteTwist(RpcValue.DateTime(1493832123000, -5400), "8DF182D3308815");
        testReadWriteTwist(RpcValue.DateTime(1493826723923, 0), "8DF1961334BEB4");
    }
}
