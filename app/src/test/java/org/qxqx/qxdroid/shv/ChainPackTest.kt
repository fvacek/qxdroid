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
}
