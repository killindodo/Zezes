package com.example.zezes

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Fat32Formatter {
    private const val SIGNATURE_OFFSET = 510

    @Throws(IOException::class)
    fun format(blockDevice: BlockDeviceDriver) {
        val totalBlocks = blockDevice.blocks
        val blockSize = blockDevice.blockSize
        val mbr = ByteBuffer.allocate(blockSize).order(ByteOrder.LITTLE_ENDIAN)
        mbr.position(446)
        mbr.put(Byte.MIN_VALUE)
        mbr.put(0.toByte())
        mbr.put(1.toByte())
        mbr.put(0.toByte())
        mbr.put(12.toByte())
        mbr.put(0.toByte())
        mbr.put(0.toByte())
        mbr.put(0.toByte())
        mbr.putInt(2048)
        mbr.putInt((totalBlocks - 2048).toInt())
        mbr.position(SIGNATURE_OFFSET)
        mbr.put(0x55.toByte())
        mbr.put(0xAA.toByte())
        mbr.flip()
        blockDevice.write(0L, mbr)

        val vbr = ByteBuffer.allocate(blockSize).order(ByteOrder.LITTLE_ENDIAN)
        vbr.put(0xEB.toByte())
        vbr.put(0x58.toByte())
        vbr.put(0x90.toByte())
        vbr.position(3)
        vbr.put("MSDOS5.0".toByteArray(Charsets.UTF_8))
        vbr.position(11)
        vbr.putShort(blockSize.toShort())
        vbr.put(8.toByte())
        vbr.putShort(32.toShort())
        vbr.put(2.toByte())
        vbr.putShort(0.toShort())
        vbr.putShort(0.toShort())
        vbr.put(0xF8.toByte())
        vbr.putShort(0.toShort())
        vbr.putShort(0.toShort())
        vbr.putShort(0.toShort())
        vbr.putInt(2048)
        vbr.putInt((totalBlocks - 2048).toInt())
        val sectorsPerFat = ((totalBlocks - 2048) / 1024).toInt()
        vbr.putInt(sectorsPerFat)
        vbr.putShort(0.toShort())
        vbr.putShort(0.toShort())
        vbr.putInt(2)
        vbr.putShort(1.toShort())
        vbr.putShort(6.toShort())
        vbr.position(SIGNATURE_OFFSET)
        vbr.put(0x55.toByte())
        vbr.put(0xAA.toByte())
        vbr.flip()
        blockDevice.write(2048L, vbr)

        val zeroBuffer = ByteBuffer.allocate(blockSize * 32)
        for (i in 0 until 11) {
            zeroBuffer.clear()
            blockDevice.write(2080L + (i.toLong() * 32L), zeroBuffer)
        }
    }
}
