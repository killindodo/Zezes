package com.example.zezes

import de.waldheinz.fs.BlockDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer

class LibaumsBlockDevice(private val driver: BlockDeviceDriver) : BlockDevice {
    override fun getSize(): Long = driver.blocks * driver.blockSize.toLong()

    override fun getSectorSize(): Int = driver.blockSize

    @Throws(IOException::class)
    override fun read(offset: Long, buffer: ByteBuffer) {
        val lba = offset / driver.blockSize.toLong()
        driver.read(lba, buffer)
    }

    @Throws(IOException::class)
    override fun write(offset: Long, buffer: ByteBuffer) {
        val lba = offset / driver.blockSize.toLong()
        driver.write(lba, buffer)
    }

    override fun flush() {
        try {
            driver.init()
        } catch (_: Exception) {
        }
    }

    override fun close() {}

    override fun isReadOnly(): Boolean = false

    override fun isClosed(): Boolean = false
}
