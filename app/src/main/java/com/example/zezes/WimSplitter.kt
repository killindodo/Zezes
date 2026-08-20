package com.example.zezes

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WimSplitter {
    private const val WIM_HEADER_SIZE = 208
    private const val OFFSET_FLAGS = 16
    private const val OFFSET_GUID = 24
    private const val OFFSET_PART_NUMBER = 40
    private const val OFFSET_TOTAL_PARTS = 42
    private const val FLAG_SPANNED = 0x08

    @Throws(Exception::class)
    fun split(
        inputStream: InputStream,
        totalSize: Long,
        maxChunkSize: Long,
        outputStreamProvider: (part: Int) -> OutputStream
    ) {
        val header = ByteArray(WIM_HEADER_SIZE)
        if (inputStream.read(header) != WIM_HEADER_SIZE) {
            throw Exception("Invalid WIM header")
        }

        val totalParts = (((totalSize - WIM_HEADER_SIZE) / maxChunkSize) + 1).toInt()
        val buffer = ByteArray(1024 * 1024)

        for (part in 1..totalParts) {
            val outputStream = outputStreamProvider(part)
            try {
                val partHeader = header.copyOf()
                val byteBuffer = ByteBuffer.wrap(partHeader).order(ByteOrder.LITTLE_ENDIAN)
                val currentFlags = byteBuffer.getInt(OFFSET_FLAGS)
                byteBuffer.putInt(OFFSET_FLAGS, currentFlags or FLAG_SPANNED)
                byteBuffer.putShort(OFFSET_PART_NUMBER, part.toShort())
                byteBuffer.putShort(OFFSET_TOTAL_PARTS, totalParts.toShort())

                outputStream.write(partHeader)

                val bytesToReadThisPart = if (part == totalParts) {
                    (totalSize - WIM_HEADER_SIZE) - ((part - 1).toLong() * maxChunkSize)
                } else {
                    maxChunkSize
                }

                var remaining = bytesToReadThisPart
                while (remaining > 0) {
                    val toRead = Math.min(buffer.size.toLong(), remaining).toInt()
                    val read = inputStream.read(buffer, 0, toRead)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            } finally {
                outputStream.close()
            }
        }
    }
}
