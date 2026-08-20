package com.example.zezes

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.usb.UsbCommunication
import java.nio.ByteBuffer

class CustomUsbCommunication(
    private val connection: UsbDeviceConnection,
    override val usbInterface: UsbInterface,
    override val outEndpoint: UsbEndpoint,
    override val inEndpoint: UsbEndpoint
) : UsbCommunication {

    override fun bulkInTransfer(dest: ByteBuffer): Int {
        val bytesRead: Int
        if (dest.hasArray()) {
            bytesRead = connection.bulkTransfer(
                inEndpoint,
                dest.array(),
                dest.arrayOffset() + dest.position(),
                dest.remaining(),
                5000
            )
        } else {
            val temp = ByteArray(dest.remaining())
            val read = connection.bulkTransfer(inEndpoint, temp, temp.size, 5000)
            if (read > 0) {
                dest.put(temp, 0, read)
            }
            bytesRead = read
        }
        if (bytesRead > 0 && dest.hasArray()) {
            dest.position(dest.position() + bytesRead)
        }
        return bytesRead
    }

    override fun bulkOutTransfer(src: ByteBuffer): Int {
        val bytesWritten: Int
        if (src.hasArray()) {
            bytesWritten = connection.bulkTransfer(
                outEndpoint,
                src.array(),
                src.arrayOffset() + src.position(),
                src.remaining(),
                5000
            )
        } else {
            val temp = ByteArray(src.remaining())
            src.get(temp)
            bytesWritten = connection.bulkTransfer(outEndpoint, temp, temp.size, 5000)
        }
        if (bytesWritten > 0 && src.hasArray()) {
            src.position(src.position() + bytesWritten)
        }
        return bytesWritten
    }

    override fun clearFeatureHalt(endpoint: UsbEndpoint) {
        connection.controlTransfer(2 or 0, 1, 0, endpoint.address, null, 0, 1000)
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int
    ): Int {
        return connection.controlTransfer(requestType, request, value, index, buffer, length, 1000)
    }

    override fun resetDevice() {
        connection.controlTransfer(1 or 32, 255, 0, usbInterface.id, null, 0, 1000)
    }

    override fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }
}
