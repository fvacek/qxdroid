package org.qxqx.qxdroid

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.qxqx.qxdroid.SerialProtocolManager.Companion.TAG

data class DataFrame(
    val command: Int,
    val data: ByteArray,
    val ok: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DataFrame

        if (command != other.command) return false
        if (!data.contentEquals(other.data)) return false
        if (ok != other.ok) return false

        return true
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + data.contentHashCode()
        result = 31 * result + ok.hashCode()
        return result
    }
    companion object {
        fun fromData(frame: ByteArray) : DataFrame {
            val command = frame[1].toInt() and 0xFF
            val length = frame[2].toInt() and 0xFF
            val data = frame.copyOfRange(3, 3 + length)
            val receivedCrcBytes = frame.copyOfRange(3 + length, 5 + length)

            val dataForCrc = frame.copyOfRange(1, 3 + length)
            val calculatedCrc = CrcCalculator.crc(dataForCrc)
            val receivedCrc = ((receivedCrcBytes[0].toInt() and 0xFF) shl 8) or (receivedCrcBytes[1].toInt() and 0xFF)

            val isCrcOk = calculatedCrc == receivedCrc && data.size == length
            val dataFrame = DataFrame(
                command = command,
                data = data,
                ok = isCrcOk
            )
            return dataFrame
        }
    }
}

class SerialProtocolManager(
    private val onRawHexData: (String) -> Unit,
    private val onProtocolMessage: (String) -> Unit,
    private val onError: (Exception) -> Unit
) : SerialInputOutputManager.Listener {

    private val dataBuffer = mutableListOf<Byte>()
    private var serialInputOutputManager: SerialInputOutputManager? = null

    fun start(port: UsbSerialPort) {
        if (serialInputOutputManager == null) {
            Log.d(TAG, "Starting SerialInputOutputManager in binary mode.")
            serialInputOutputManager = SerialInputOutputManager(port, this)
            serialInputOutputManager?.start()
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping SerialInputOutputManager.")
        serialInputOutputManager?.stop()
        serialInputOutputManager = null
    }

    override fun onNewData(data: ByteArray) {
        val hex = bytesToHex(data)
        Log.d(TAG, "onNewData: Received ${data.size} bytes: $hex")
        onRawHexData(hex)
        dataBuffer.addAll(data.toList())
        processDataBuffer()
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "SerialPort Error", e)
        onError(e)
    }

    private fun processDataBuffer() {
        Log.d(TAG, "processDataBuffer: Starting with buffer size ${dataBuffer.size}")
        while (true) {
            // Find the start of a frame
            val stxIndex = dataBuffer.indexOf(STX)
            if (stxIndex == -1) {
                Log.d(TAG, "processDataBuffer: No STX found, waiting for more data.")
                return // No STX found, wait for more data
            }
            Log.d(TAG, "processDataBuffer: Found STX at index $stxIndex")

            // Discard any data before STX
            if (stxIndex > 0) {
                Log.d(TAG, "processDataBuffer: Discarding $stxIndex bytes before STX.")
                dataBuffer.subList(0, stxIndex).clear()
            }

            // Need at least 3 bytes for STX, command, and length
            if (dataBuffer.size < 3) {
                Log.d(TAG, "processDataBuffer: Buffer size ${dataBuffer.size} is too small for a header, waiting.")
                return
            }

            // Check if it's the extended protocol (command >= 0x80)
            val command = dataBuffer[1]
            if (command.toInt() and 0xFF < 0x80) {
                val msg = "Unsupported frame (CMD < 0x80): ${bytesToHex(byteArrayOf(command))}"
                onProtocolMessage(msg)
                Log.w(TAG, "processDataBuffer: Unsupported frame (CMD < 0x80), discarding STX.")
                dataBuffer.removeAt(0) // Discard STX and continue
                continue
            }

            val dataLength = dataBuffer[2].toInt() and 0xFF
            val frameLength = 1 + 1 + 1 + dataLength + 2 + 1 // STX, CMD, LEN, DATA, CRC, ETX
            Log.d(TAG, "processDataBuffer: Expected frame length is $frameLength (data length: $dataLength)")

            if (dataBuffer.size < frameLength) {
                Log.d(TAG, "processDataBuffer: Buffer size ${dataBuffer.size} is too small for full frame, waiting.")
                return // Not enough data for the full frame, wait
            }

            // Check for ETX at the end of the frame
            if (dataBuffer[frameLength - 1] != ETX) {
                val msg = "Malformed frame (bad ETX). Discarding STX."
                onProtocolMessage(msg)
                Log.w(TAG, "processDataBuffer: Malformed frame (bad ETX). Discarding STX.")
                dataBuffer.removeAt(0) // Discard STX and retry
                continue
            }

            // If we are here, we have a complete frame
            Log.d(TAG, "processDataBuffer: Found complete frame of length $frameLength.")
            val frame = dataBuffer.take(frameLength).toByteArray()
            parseAndLogFrame(frame)

            // Remove the processed frame from the buffer
            Log.d(TAG, "processDataBuffer: Removing processed frame from buffer.")
            dataBuffer.subList(0, frameLength).clear()
        }
    }

    private fun parseAndLogFrame(frame: ByteArray) {
        val dataFrame = DataFrame.fromData(frame)
        val logMessage = "${bytesToHex(byteArrayOf(dataFrame.command.toByte()))} | ${bytesToHex(dataFrame.data)} | $dataFrame.ok"
        Log.i(TAG, "parseAndLogFrame: $logMessage")
        onProtocolMessage(logMessage)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it).uppercase() }
    }

    companion object {
        private const val TAG = "SerialProtocolManager"
        private const val STX: Byte = 0x02
        private const val ETX: Byte = 0x03
        private const val ACK: Byte = 0x06
        private const val NAK: Byte = 0x15
        private const val DLE: Byte = 0x10
    }
}