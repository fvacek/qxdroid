package org.qxqx.qxdroid.si

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.qxqx.qxdroid.si.SiDataFrame
import org.qxqx.qxdroid.bytesToHex
import timber.log.Timber

class SerialPortManager(
    private val onRawData: (ByteArray) -> Unit,
    private val onDataFrame: (SiDataFrame) -> Unit,
    private val onError: (Exception) -> Unit
) : SerialInputOutputManager.Listener {

    private val dataBuffer = mutableListOf<Byte>()
    private var serialInputOutputManager: SerialInputOutputManager? = null

    fun sendDataFrame(frame: SiDataFrame) {
        val data = frame.toByteArray()
        Timber.d("Sending data frame: ${bytesToHex(data)}.")
        serialInputOutputManager?.writeAsync(data)
        //port?.let { p ->
        //    serialExecutor.submit {
        //        try {
        //            p.write(data, 500)
        //            Log.d(TAG, "Send OK")
        //        } catch (e: IOException) {
        //            Log.e(TAG, "Error writing to serial port", e)
        //            onError(e)
        //        }
        //    }
        //} ?: Log.w(TAG, "Port not available, not sending data.")
    }

    fun start(port: UsbSerialPort) {
        if (serialInputOutputManager == null) {
            Timber.d("Starting SerialInputOutputManager in binary mode.")
            serialInputOutputManager = SerialInputOutputManager(port, this)
            serialInputOutputManager?.start()
        }
    }

    fun stop() {
        Timber.d("Stopping SerialPortManager.")
        serialInputOutputManager?.let {
            it.listener = null
            it.stop()
        }
        serialInputOutputManager = null
    }

    override fun onNewData(data: ByteArray) {
        Timber.d("Serial data received ${data.size} bytes: ${bytesToHex(data)}")
        onRawData(data)
        dataBuffer.addAll(data.toList())
        processDataBuffer()
    }

    override fun onRunError(e: Exception) {
        Timber.e(e, "SerialPort Error")
        onError(e)
    }

    private fun processDataBuffer() {
        //Log.d(TAG, "processDataBuffer: Starting with buffer size ${dataBuffer.size}")
        while (true) {
            // Find the start of a frame
            val stxIndex = dataBuffer.indexOf(STX)
            if (stxIndex == -1) {
                //Log.d(TAG, "processDataBuffer: No STX found, waiting for more data.")
                return // No STX found, wait for more data
            }
            //Log.d(TAG, "processDataBuffer: Found STX at index $stxIndex")

            // Discard any data before STX
            if (stxIndex > 0) {
                Timber.d("processDataBuffer: Discarding $stxIndex bytes before STX.")
                dataBuffer.subList(0, stxIndex).clear()
            }

            // Need at least 3 bytes for STX, command, and length
            if (dataBuffer.size < 3) {
                //Log.d(TAG, "processDataBuffer: Buffer size ${dataBuffer.size} is too small for a header, waiting.")
                return
            }

            // Check if it's the extended protocol (command >= 0x80)
            val command = dataBuffer[1]
            if (command.toInt() and 0xFF < 0x80) {
                val msg = "Unsupported frame (CMD < 0x80): ${bytesToHex(byteArrayOf(command))}"
                Timber.w("processDataBuffer: Unsupported frame (CMD < 0x80), discarding STX.")
                dataBuffer.removeAt(0) // Discard STX and continue
                continue
            }

            val dataLength = dataBuffer[2].toInt() and 0xFF
            val frameLength = 1 + 1 + 1 + dataLength + 2 + 1 // STX, CMD, LEN, DATA, CRC, ETX
            //Log.d(TAG, "processDataBuffer: Expected frame length is $frameLength (data length: $dataLength)")

            if (dataBuffer.size < frameLength) {
                //Log.d(TAG, "processDataBuffer: Buffer size ${dataBuffer.size} is too small for full frame, waiting.")
                return // Not enough data for the full frame, wait
            }

            // Check for ETX at the end of the frame
            if (dataBuffer[frameLength - 1] != ETX) {
                val msg = "Malformed frame (bad ETX). Discarding STX."
                Timber.w("processDataBuffer: Malformed frame (bad ETX). Discarding STX.")
                dataBuffer.removeAt(0) // Discard STX and retry
                continue
            }

            // If we are here, we have a complete frame
            //Log.d(TAG, "processDataBuffer: Found complete frame of length $frameLength.")
            val frame = dataBuffer.take(frameLength).toByteArray()
            parseFrame(frame)

            // Remove the processed frame from the buffer
            //Log.d(TAG, "processDataBuffer: Removing processed frame from buffer.")
            dataBuffer.subList(0, frameLength).clear()
        }
    }

    private fun parseFrame(frame: ByteArray) {
        try {
            val dataFrame = SiDataFrame.Companion.fromData(frame)
            Timber.i("New frame: $dataFrame")
            onDataFrame(dataFrame)
        } catch (e: Exception) {
            Timber.e("parseAndLogFrame: Error parsing frame: ${e.message}")
        }
    }

    companion object {
        private const val STX: Byte = 0x02
        private const val ETX: Byte = 0x03
        private const val ACK: Byte = 0x06
        private const val NAK: Byte = 0x15
        private const val DLE: Byte = 0x10
    }
}